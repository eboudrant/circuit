// Copyright (C) 2026 Slack Technologies, LLC
// SPDX-License-Identifier: Apache-2.0
package com.slack.circuit.subcircuit.codegen

import com.google.auto.service.AutoService
import com.google.devtools.ksp.getAllSuperTypes
import com.google.devtools.ksp.processing.Resolver
import com.google.devtools.ksp.processing.SymbolProcessor
import com.google.devtools.ksp.processing.SymbolProcessorEnvironment
import com.google.devtools.ksp.processing.SymbolProcessorProvider
import com.google.devtools.ksp.symbol.ClassKind
import com.google.devtools.ksp.symbol.KSAnnotated
import com.google.devtools.ksp.symbol.KSAnnotation
import com.google.devtools.ksp.symbol.KSClassDeclaration
import com.google.devtools.ksp.symbol.KSFile
import com.google.devtools.ksp.symbol.KSFunctionDeclaration
import com.google.devtools.ksp.symbol.KSType
import com.squareup.kotlinpoet.AnnotationSpec
import com.squareup.kotlinpoet.ClassName
import com.squareup.kotlinpoet.FileSpec
import com.squareup.kotlinpoet.FunSpec
import com.squareup.kotlinpoet.KModifier
import com.squareup.kotlinpoet.ParameterizedTypeName.Companion.parameterizedBy
import com.squareup.kotlinpoet.PropertySpec
import com.squareup.kotlinpoet.STAR
import com.squareup.kotlinpoet.TypeName
import com.squareup.kotlinpoet.TypeSpec
import com.squareup.kotlinpoet.ksp.addOriginatingKSFile
import com.squareup.kotlinpoet.ksp.toClassName
import com.squareup.kotlinpoet.ksp.writeTo

private const val SUB_CIRCUIT_INJECT_FQNAME = "com.slack.circuit.subcircuit.SubCircuitInject"
private const val ASSISTED_FACTORY_FQNAME = "dagger.assisted.AssistedFactory"
private const val OPTION_CODEGEN_MODE = "subcircuit.codegen.mode"

private val SUB_PRESENTER_FACTORY_CN =
  ClassName("com.slack.circuit.subcircuit", "SubPresenterFactory")
private val SUB_UI_FACTORY_CN = ClassName("com.slack.circuit.subcircuit", "SubUiFactory")
private val SUB_PRESENTER_CN = ClassName("com.slack.circuit.subcircuit", "SubPresenter")
private val SUB_UI_CN = ClassName("com.slack.circuit.subcircuit", "SubUi")
private val SUB_SCREEN_CN = ClassName("com.slack.circuit.subcircuit", "SubScreen")
private val COMPOSABLE_CN = ClassName("androidx.compose.runtime", "Composable")

private val CONTRIBUTES_MULTIBINDING_CN =
  ClassName("com.squareup.anvil.annotations", "ContributesMultibinding")

private val METRO_CONTRIBUTES_INTO_SET_CN =
  ClassName("dev.zacsweers.metro.annotations", "ContributesIntoSet")

private val INJECT_CN = ClassName("javax.inject", "Inject")

private val DAGGER_MODULE_CN = ClassName("dagger", "Module")
private val DAGGER_BINDS_CN = ClassName("dagger", "Binds")
private val DAGGER_INSTALL_IN_CN = ClassName("dagger.hilt", "InstallIn")
private val DAGGER_INTO_SET_CN = ClassName("dagger.multibindings", "IntoSet")

/** Codegen mode for dependency injection framework support. */
public enum class CodegenMode {
  /** Uses Anvil's @ContributesMultibinding with @Inject on constructor */
  ANVIL,
  /** Uses Metro's @ContributesIntoSet with @Inject on class */
  METRO,
  /**
   * Uses Hilt. Generates an `@Inject` constructor on the factory plus a separate `@Module` /
   * `@InstallIn` module that `@Binds @IntoSet` the factory into the multibinding set. Hilt only
   * supports JVM & Android targets.
   */
  HILT,
}

/**
 * KSP Symbol Processor that generates factory implementations for `@SubCircuitInject` annotations.
 *
 * For presenter classes: Annotate the `@AssistedFactory` interface with `@SubCircuitInject`. The
 * factory doesn't need to be nested in the presenter class.
 *
 * For `@Composable` UI functions: Annotate the function directly with `@SubCircuitInject`.
 *
 * Supports Anvil (default), Metro, and Hilt codegen modes via the `subcircuit.codegen.mode` option.
 */
public class SubCircuitSymbolProcessor(private val env: SymbolProcessorEnvironment) :
  SymbolProcessor {

  private lateinit var subCircuitInjectType: KSType

  private val codegenMode: CodegenMode by lazy {
    val modeOption = env.options[OPTION_CODEGEN_MODE]
    when (modeOption?.lowercase()) {
      "metro" -> CodegenMode.METRO
      "hilt" -> CodegenMode.HILT
      "anvil",
      null -> CodegenMode.ANVIL
      else -> {
        env.logger.warn("Unknown codegen mode '$modeOption', defaulting to ANVIL")
        CodegenMode.ANVIL
      }
    }
  }

  override fun process(resolver: Resolver): List<KSAnnotated> {
    subCircuitInjectType =
      resolver
        .getClassDeclarationByName(resolver.getKSNameFromString(SUB_CIRCUIT_INJECT_FQNAME))
        ?.asType(emptyList()) ?: return emptyList()

    resolver.getSymbolsWithAnnotation(SUB_CIRCUIT_INJECT_FQNAME).forEach { symbol ->
      when (symbol) {
        is KSClassDeclaration -> processClassAnnotation(symbol)
        is KSFunctionDeclaration -> processUiFunction(symbol)
        else -> {
          env.logger.error(
            "@SubCircuitInject can only be applied to @AssistedFactory interfaces or @Composable functions",
            symbol,
          )
        }
      }
    }

    return emptyList()
  }

  private fun KSAnnotated.getSubCircuitInjectAnnotation(): KSAnnotation? = annotations.firstOrNull {
    it.annotationType.resolve() == subCircuitInjectType
  }

  private fun KSAnnotation.getScreenType(): ClassName? {
    val screenArg = arguments.find { it.name?.asString() == "screen" }
    return (screenArg?.value as? KSType)?.toClassName()
  }

  private fun KSAnnotation.getScopeType(): ClassName? {
    val scopeArg = arguments.find { it.name?.asString() == "scope" }
    return (scopeArg?.value as? KSType)?.toClassName()
  }

  private fun KSAnnotated.hasAnnotation(fqName: String): Boolean = annotations.any {
    it.annotationType.resolve().declaration.qualifiedName?.asString() == fqName
  }

  @Suppress("ReturnCount")
  private fun processClassAnnotation(classDecl: KSClassDeclaration) {
    if (classDecl.classKind != ClassKind.INTERFACE) {
      env.logger.error(
        "@SubCircuitInject on classes is only valid for @AssistedFactory interfaces",
        classDecl,
      )
      return
    }

    if (!classDecl.hasAnnotation(ASSISTED_FACTORY_FQNAME)) {
      env.logger.error(
        "@SubCircuitInject must be combined with @AssistedFactory on factory interfaces",
        classDecl,
      )
      return
    }

    val annotation = classDecl.getSubCircuitInjectAnnotation()
    if (annotation == null) {
      env.logger.error("Could not read @SubCircuitInject annotation", classDecl)
      return
    }

    val screenClassName = annotation.getScreenType()
    val scopeClassName = annotation.getScopeType()

    if (screenClassName == null || scopeClassName == null) {
      env.logger.error("@SubCircuitInject requires both screen and scope parameters", classDecl)
      return
    }

    val createMethod =
      classDecl.getAllFunctions().firstOrNull { func ->
        func.simpleName.asString() == "create" || func.simpleName.asString().startsWith("create")
      }

    if (createMethod == null) {
      env.logger.error("@AssistedFactory interface must have a create method", classDecl)
      return
    }

    val presenterType = createMethod.returnType?.resolve()
    if (presenterType == null) {
      env.logger.error("Could not determine presenter type from factory create method", classDecl)
      return
    }

    val presenterDecl = presenterType.declaration as? KSClassDeclaration
    if (presenterDecl == null) {
      env.logger.error("Factory create method must return a presenter class", classDecl)
      return
    }

    val implementsSubPresenter =
      presenterDecl.getAllSuperTypes().any {
        it.declaration.qualifiedName?.asString() == SUB_PRESENTER_CN.canonicalName
      }

    if (!implementsSubPresenter) {
      env.logger.error(
        "Presenter class ${presenterDecl.simpleName.asString()} must implement SubPresenter",
        classDecl,
      )
      return
    }

    generatePresenterFactory(
      factoryInterface = classDecl,
      screenClassName = screenClassName,
      scopeClassName = scopeClassName,
    )
  }

  private fun generatePresenterFactory(
    factoryInterface: KSClassDeclaration,
    screenClassName: ClassName,
    scopeClassName: ClassName,
  ) {
    val factoryClassName = factoryInterface.toClassName()
    val generatedClassName = "${factoryClassName.simpleNames.joinToString("_")}_SubPresenterFactory"
    val packageName = factoryClassName.packageName
    val originatingFile = factoryInterface.containingFile!!

    val factorySpec =
      when (codegenMode) {
        CodegenMode.ANVIL ->
          buildAnvilPresenterFactory(
            generatedClassName,
            factoryClassName,
            screenClassName,
            scopeClassName,
          )
        CodegenMode.METRO ->
          buildMetroPresenterFactory(
            generatedClassName,
            factoryClassName,
            screenClassName,
            scopeClassName,
          )
        CodegenMode.HILT ->
          buildHiltPresenterFactory(generatedClassName, factoryClassName, screenClassName)
      }

    writeFile(packageName, generatedClassName, factorySpec, originatingFile)

    if (codegenMode == CodegenMode.HILT) {
      val moduleSpec =
        buildHiltModule(
          factoryImplClassName = ClassName(packageName, generatedClassName),
          boundType = SUB_PRESENTER_FACTORY_CN,
          scopeClassName = scopeClassName,
        )
      writeFile(packageName, generatedClassName + "Module", moduleSpec, originatingFile)
    }
  }

  private fun buildAnvilPresenterFactory(
    generatedClassName: String,
    factoryClassName: ClassName,
    screenClassName: ClassName,
    scopeClassName: ClassName,
  ): TypeSpec.Builder =
    TypeSpec.classBuilder(generatedClassName)
      .addAnnotation(
        AnnotationSpec.builder(CONTRIBUTES_MULTIBINDING_CN)
          .addMember("%T::class", scopeClassName)
          .build()
      )
      .primaryConstructor(
        FunSpec.constructorBuilder()
          .addAnnotation(INJECT_CN)
          .addParameter("factory", factoryClassName)
          .build()
      )
      .addProperty(
        PropertySpec.builder("factory", factoryClassName)
          .initializer("factory")
          .addModifiers(KModifier.PRIVATE)
          .build()
      )
      .addSuperinterface(SUB_PRESENTER_FACTORY_CN)
      .addFunction(buildPresenterCreateFunction(screenClassName))

  private fun buildMetroPresenterFactory(
    generatedClassName: String,
    factoryClassName: ClassName,
    screenClassName: ClassName,
    scopeClassName: ClassName,
  ): TypeSpec.Builder =
    TypeSpec.classBuilder(generatedClassName)
      .addAnnotation(INJECT_CN)
      .addAnnotation(
        AnnotationSpec.builder(METRO_CONTRIBUTES_INTO_SET_CN)
          .addMember("%T::class", scopeClassName)
          .build()
      )
      .primaryConstructor(
        FunSpec.constructorBuilder().addParameter("factory", factoryClassName).build()
      )
      .addProperty(
        PropertySpec.builder("factory", factoryClassName)
          .initializer("factory")
          .addModifiers(KModifier.PRIVATE)
          .build()
      )
      .addSuperinterface(SUB_PRESENTER_FACTORY_CN)
      .addFunction(buildPresenterCreateFunction(screenClassName))

  private fun buildHiltPresenterFactory(
    generatedClassName: String,
    factoryClassName: ClassName,
    screenClassName: ClassName,
  ): TypeSpec.Builder =
    TypeSpec.classBuilder(generatedClassName)
      .primaryConstructor(
        FunSpec.constructorBuilder()
          .addAnnotation(INJECT_CN)
          .addParameter("factory", factoryClassName)
          .build()
      )
      .addProperty(
        PropertySpec.builder("factory", factoryClassName)
          .initializer("factory")
          .addModifiers(KModifier.PRIVATE)
          .build()
      )
      .addSuperinterface(SUB_PRESENTER_FACTORY_CN)
      .addFunction(buildPresenterCreateFunction(screenClassName))

  /**
   * Builds the Hilt `@Module` that binds the generated factory implementation into the multibinding
   * set for [boundType] (either [SUB_PRESENTER_FACTORY_CN] or [SUB_UI_FACTORY_CN]).
   */
  private fun buildHiltModule(
    factoryImplClassName: ClassName,
    boundType: ClassName,
    scopeClassName: ClassName,
  ): TypeSpec.Builder {
    val factorySimpleName = factoryImplClassName.simpleName
    val bindFunction =
      FunSpec.builder("bind$factorySimpleName")
        .addModifiers(KModifier.ABSTRACT)
        .addAnnotation(DAGGER_BINDS_CN)
        .addAnnotation(DAGGER_INTO_SET_CN)
        .addParameter(factorySimpleName.replaceFirstChar { it.lowercase() }, factoryImplClassName)
        .returns(boundType)
        .build()
    return TypeSpec.classBuilder(factorySimpleName + "Module")
      .addModifiers(KModifier.ABSTRACT)
      .addAnnotation(DAGGER_MODULE_CN)
      .addAnnotation(
        AnnotationSpec.builder(DAGGER_INSTALL_IN_CN).addMember("%T::class", scopeClassName).build()
      )
      .addFunction(bindFunction)
  }

  private fun writeFile(
    packageName: String,
    className: String,
    typeSpec: TypeSpec.Builder,
    originatingFile: KSFile,
  ) {
    FileSpec.builder(packageName, className)
      .addFileComment("Generated by ${SubCircuitSymbolProcessor::class.simpleName}")
      .addType(typeSpec.addOriginatingKSFile(originatingFile).build())
      .build()
      .writeTo(env.codeGenerator, aggregating = false)
  }

  private fun buildPresenterCreateFunction(screenClassName: ClassName): FunSpec =
    FunSpec.builder("create")
      .addModifiers(KModifier.OVERRIDE)
      .addParameter("screen", SUB_SCREEN_CN.parameterizedBy(STAR))
      .returns(SUB_PRESENTER_CN.parameterizedBy(STAR, STAR).copy(nullable = true))
      .addStatement("return if (screen is %T) factory.create(screen) else null", screenClassName)
      .build()

  private fun processUiFunction(funcDecl: KSFunctionDeclaration) {
    val isComposable =
      funcDecl.annotations.any {
        it.annotationType.resolve().declaration.qualifiedName?.asString() ==
          COMPOSABLE_CN.canonicalName
      }

    if (!isComposable) {
      env.logger.error(
        "@SubCircuitInject on functions is only valid for @Composable functions",
        funcDecl,
      )
      return
    }

    val annotation = funcDecl.getSubCircuitInjectAnnotation()
    if (annotation == null) {
      env.logger.error("Could not read @SubCircuitInject annotation", funcDecl)
      return
    }

    val screenClassName = annotation.getScreenType()
    val scopeClassName = annotation.getScopeType()

    if (screenClassName == null || scopeClassName == null) {
      env.logger.error("@SubCircuitInject requires both screen and scope parameters", funcDecl)
      return
    }

    val parameters = funcDecl.parameters
    if (parameters.isEmpty()) {
      env.logger.error(
        "@SubCircuitInject UI functions must have at least a state parameter",
        funcDecl,
      )
      return
    }

    val stateParameter = parameters.first()
    val stateType = stateParameter.type.resolve().toClassName()

    val funcName = funcDecl.simpleName.asString()
    val packageName = funcDecl.packageName.asString()
    val generatedClassName = "${funcName}_SubUiFactory"
    val originatingFile = funcDecl.containingFile!!

    val factorySpec =
      when (codegenMode) {
        CodegenMode.ANVIL ->
          buildAnvilUiFactory(
            generatedClassName,
            funcName,
            stateType,
            screenClassName,
            scopeClassName,
          )
        CodegenMode.METRO ->
          buildMetroUiFactory(
            generatedClassName,
            funcName,
            stateType,
            screenClassName,
            scopeClassName,
          )
        CodegenMode.HILT ->
          buildHiltUiFactory(generatedClassName, funcName, stateType, screenClassName)
      }

    writeFile(packageName, generatedClassName, factorySpec, originatingFile)

    if (codegenMode == CodegenMode.HILT) {
      val moduleSpec =
        buildHiltModule(
          factoryImplClassName = ClassName(packageName, generatedClassName),
          boundType = SUB_UI_FACTORY_CN,
          scopeClassName = scopeClassName,
        )
      writeFile(packageName, generatedClassName + "Module", moduleSpec, originatingFile)
    }
  }

  private fun buildAnvilUiFactory(
    generatedClassName: String,
    funcName: String,
    stateType: ClassName,
    screenClassName: ClassName,
    scopeClassName: ClassName,
  ): TypeSpec.Builder =
    TypeSpec.classBuilder(generatedClassName)
      .addAnnotation(
        AnnotationSpec.builder(CONTRIBUTES_MULTIBINDING_CN)
          .addMember("%T::class", scopeClassName)
          .build()
      )
      .primaryConstructor(FunSpec.constructorBuilder().addAnnotation(INJECT_CN).build())
      .addSuperinterface(SUB_UI_FACTORY_CN)
      .addFunction(buildUiCreateFunction(funcName, stateType, screenClassName))

  private fun buildMetroUiFactory(
    generatedClassName: String,
    funcName: String,
    stateType: ClassName,
    screenClassName: ClassName,
    scopeClassName: ClassName,
  ): TypeSpec.Builder =
    TypeSpec.classBuilder(generatedClassName)
      .addAnnotation(INJECT_CN)
      .addAnnotation(
        AnnotationSpec.builder(METRO_CONTRIBUTES_INTO_SET_CN)
          .addMember("%T::class", scopeClassName)
          .build()
      )
      .primaryConstructor(FunSpec.constructorBuilder().build())
      .addSuperinterface(SUB_UI_FACTORY_CN)
      .addFunction(buildUiCreateFunction(funcName, stateType, screenClassName))

  private fun buildHiltUiFactory(
    generatedClassName: String,
    funcName: String,
    stateType: ClassName,
    screenClassName: ClassName,
  ): TypeSpec.Builder =
    TypeSpec.classBuilder(generatedClassName)
      .primaryConstructor(FunSpec.constructorBuilder().addAnnotation(INJECT_CN).build())
      .addSuperinterface(SUB_UI_FACTORY_CN)
      .addFunction(buildUiCreateFunction(funcName, stateType, screenClassName))

  private fun buildUiCreateFunction(
    funcName: String,
    stateType: ClassName,
    screenClassName: ClassName,
  ): FunSpec =
    FunSpec.builder("create")
      .addModifiers(KModifier.OVERRIDE)
      .addParameter("screen", SUB_SCREEN_CN.parameterizedBy(STAR))
      .returns(SUB_UI_CN.parameterizedBy(STAR).copy(nullable = true))
      .beginControlFlow("return if (screen is %T)", screenClassName)
      .addStatement(
        "%T<%T> { state, modifier -> %L(state, modifier) }",
        SUB_UI_CN,
        stateType,
        funcName,
      )
      .nextControlFlow("else")
      .addStatement("null")
      .endControlFlow()
      .build()

  @AutoService(SymbolProcessorProvider::class)
  public class Provider : SymbolProcessorProvider {
    override fun create(environment: SymbolProcessorEnvironment): SymbolProcessor {
      return SubCircuitSymbolProcessor(environment)
    }
  }
}

private fun ClassName.parameterizedBy(vararg types: TypeName) =
  com.squareup.kotlinpoet.ParameterizedTypeName.Companion.run {
    this@parameterizedBy.parameterizedBy(*types)
  }
