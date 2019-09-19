package com.mikeescom;

import com.intellij.codeInsight.generation.PsiFieldMember;
import com.intellij.ide.util.PropertiesComponent;
import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.editor.Editor;
import com.intellij.openapi.project.Project;
import com.intellij.psi.*;
import com.intellij.psi.codeStyle.CodeStyleManager;
import com.intellij.psi.codeStyle.JavaCodeStyleManager;
import com.intellij.psi.util.PropertyUtil;
import com.intellij.psi.util.PsiUtil;
import org.apache.http.util.TextUtils;
import org.jetbrains.annotations.NonNls;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.*;

public class IntentBuilderGenerator implements Runnable {

    @NonNls
    private static final String CONTEXT_CLASS_NAME = "Context";
    @NonNls
    private static final String INTENT_CLASS_NAME = "Intent";
    @NonNls
    private static final String BUILDER_CLASS_NAME = "Builder";
    @NonNls
    private static final String BUILD_STEP_INTERFACE_NAME = "Build";
    @NonNls
    private static final String INTERFACE_NAME_PREFIX = "I";
    @NonNls
    private static final String BUILDER_SETTER_DEFAULT_PARAMETER_NAME = "val";
    @NonNls
    private static final String BUILDER_SETTER_ALTERNATIVE_PARAMETER_NAME = "value";
    @NonNls
    private static final String OVERRIDE_ANNOTATION = "java.lang.Override";

    private final Project project;
    private final PsiFile file;
    private final Editor editor;
    private final List<PsiFieldMember> mandatoryFields;
    private final PsiElementFactory psiElementFactory;
    private PsiClass builderClass;
    private PsiType contextType;
    private PsiType intentType;
    private PsiType builderType;
    private Map<String, String[]> tagsMap;

    private IntentBuilderGenerator(final Project project, final PsiFile file, final Editor editor,
                                 final List<PsiFieldMember> mandatoryFields, final Map<String, String[]> tags) {
        this.project = project;
        this.file = file;
        this.editor = editor;
        this.mandatoryFields = mandatoryFields;
        psiElementFactory = JavaPsiFacade.getInstance(project).getElementFactory();
        tagsMap = tags;
    }

    public static void generate(final Project project, final Editor editor, final PsiFile file,
                                final List<PsiFieldMember> selectedFields, Map<String, String[]> tags) {
        final Runnable builderGenerator = new IntentBuilderGenerator(project, file, editor, selectedFields, tags);
        ApplicationManager.getApplication().runWriteAction(builderGenerator);
    }

    private static EnumSet<IntentBuilderOption> currentOptions() {
        final EnumSet<IntentBuilderOption> options = EnumSet.noneOf(IntentBuilderOption.class);
        final PropertiesComponent propertiesComponent = PropertiesComponent.getInstance();
        for (final IntentBuilderOption option : IntentBuilderOption.values()) {
            final boolean currentSetting = propertiesComponent.getBoolean(option.getProperty(), false);
            if (currentSetting) {
                options.add(option);
            }
        }
        return options;
    }

    @Override
    public void run() {
        final PsiClass topLevelClass = IntentBuilderUtils.getTopLevelClass(project, file, editor);
        if (topLevelClass == null) {
            return;
        }

        final Set<IntentBuilderOption> options = currentOptions();

        final List<PsiFieldMember> finalFields = new ArrayList<PsiFieldMember>();
        final List<PsiFieldMember> nonFinalFields = new ArrayList<PsiFieldMember>();
        final List<PsiFieldMember> mandatoryNonFinalFields = new ArrayList<PsiFieldMember>();

        //generate the interfaces
        final PsiClass interfaceClass = createBuildStepInterface(options.contains(IntentBuilderOption.PUBLIC_INTERFACES));
        final PsiClassType interfaceType = psiElementFactory.createType(interfaceClass);

        //add build method
        PsiMethod methodStatement = psiElementFactory.createMethodFromText(String.format("%s %s(%s %s);"
                , INTENT_CLASS_NAME, "build", CONTEXT_CLASS_NAME, "context"), interfaceClass);

        interfaceClass.add(methodStatement);
        topLevelClass.add(interfaceClass);

        //generate mandatory interfaces
        final List<PsiClassType> mandatoryInterfaceTypes = new ArrayList<PsiClassType>();
        if(mandatoryFields != null && !mandatoryFields.isEmpty()){
            PsiClassType returnType = interfaceType;

            for(int i = mandatoryFields.size() - 1; i >= 0; i--) {
                PsiFieldMember fieldMember = mandatoryFields.get(i);
                if (!fieldMember.getElement().hasModifierProperty(PsiModifier.FINAL)
                        || options.contains(IntentBuilderOption.FINAL_SETTERS)) {

                    nonFinalFields.add(fieldMember);
                    mandatoryNonFinalFields.add(fieldMember);

                    PsiClass mInterface = generateMandatoryInterface(fieldMember, returnType, options.contains(IntentBuilderOption.PUBLIC_INTERFACES));
                    topLevelClass.add(mInterface);

                    returnType = psiElementFactory.createType(mInterface);
                    mandatoryInterfaceTypes.add(returnType);
                }
                else{
                    finalFields.add(fieldMember);
                }
            }
        }

        //create builder class
        builderClass = findOrCreateBuilderClass(topLevelClass, mandatoryInterfaceTypes, interfaceType);
        contextType = psiElementFactory.createTypeFromText(CONTEXT_CLASS_NAME, null);
        intentType = psiElementFactory.createTypeFromText(INTENT_CLASS_NAME, null);
        builderType = psiElementFactory.createTypeFromText(BUILDER_CLASS_NAME, null);

        //add a constructor to the class
        final PsiMethod constructor = generateConstructor(topLevelClass, intentType);
        addMethod(topLevelClass, null, constructor, true);

        //add the fields in Builder
        PsiElement lastAddedField = null;
        for (final PsiFieldMember fieldMember : nonFinalFields) {
            lastAddedField = findOrCreateField(builderClass, fieldMember, lastAddedField);
        }

        for (final PsiFieldMember fieldMember : finalFields) {
            lastAddedField = findOrCreateField(builderClass, fieldMember, lastAddedField);
            PsiUtil.setModifierProperty((PsiField) lastAddedField, PsiModifier.FINAL, true);
        }

        // builder constructor, accepting the final fields
        final PsiMethod builderConstructorMethod = generateBuilderConstructor(builderClass, finalFields, options);
        addMethod(builderClass, null, builderConstructorMethod, false);

        // builder copy constructor or static copy method
        if (options.contains(IntentBuilderOption.COPY_CONSTRUCTOR)) {
            final PsiMethod copyBuilderMethod = generateCopyBuilderMethod(topLevelClass, builderType,
                    finalFields,nonFinalFields, options);
            addMethod(topLevelClass, null, copyBuilderMethod, true);
        }

        // builder methods
        final PsiClassType lastInterfaceType;

        PsiElement lastAddedElement = null;
        if(!mandatoryNonFinalFields.isEmpty()) {
            PsiClassType returnType = interfaceType;

            for (int i = 0; i < mandatoryNonFinalFields.size(); i++) {
                final PsiFieldMember member = mandatoryNonFinalFields.get(i);
                final PsiMethod setterMethod = generateBuilderSetter(returnType, member, options);
                lastAddedElement = addMethod(builderClass, lastAddedElement, setterMethod, false);
                returnType = mandatoryInterfaceTypes.get(i);
            }

            lastInterfaceType = returnType;
        } else {
            lastInterfaceType = interfaceType;
        }

        //generate the static builder method
        final PsiMethod newBuilderMethod = generateNewBuilderMethod(builderType, finalFields, options, lastInterfaceType);
        addMethod(topLevelClass, null, newBuilderMethod, false);

        // builder.build() method
        final PsiMethod buildMethod = generateBuildMethod(topLevelClass, builderClass);
        addMethod(builderClass, lastAddedElement, buildMethod, false);

        //generate getters for parent class
        if(mandatoryFields != null && !mandatoryFields.isEmpty()){
            for(int i = mandatoryFields.size() - 1; i >= 0; i--) {
                PsiFieldMember fieldMember = mandatoryFields.get(i);
                String type = fieldMember.getText().split(":")[1];
                PsiType memberType = psiElementFactory.createTypeFromText(type, null);
                PsiMethod getterMethod = generateGetterMethod(fieldMember, memberType);
                topLevelClass.add(getterMethod);
            }
        }

        JavaCodeStyleManager.getInstance(project).shortenClassReferences(file);
        CodeStyleManager.getInstance(project).reformat(builderClass);
    }

    private PsiClass createBuildStepInterface(boolean isPublic){
        PsiClass buildStep =  psiElementFactory.createInterface(INTERFACE_NAME_PREFIX + BUILD_STEP_INTERFACE_NAME);
        if(buildStep.getModifierList() != null){
            buildStep.getModifierList().setModifierProperty(PsiModifier.PUBLIC, isPublic);
        }

        return buildStep;
    }

    private PsiClass generateMandatoryInterface(PsiFieldMember forMember, PsiType returnType, boolean isPublic){
        String capitalizedFieldName = IntentBuilderUtils.capitalize(forMember.getElement().getName());
        String methodName = String.format("with%s", capitalizedFieldName);
        String paramName = BUILDER_SETTER_DEFAULT_PARAMETER_NAME.equals(forMember.getElement().getName())?
                BUILDER_SETTER_ALTERNATIVE_PARAMETER_NAME:BUILDER_SETTER_DEFAULT_PARAMETER_NAME;

        PsiClass mInterface = psiElementFactory.createInterface(INTERFACE_NAME_PREFIX + capitalizedFieldName);
        if(mInterface.getModifierList() != null){
            mInterface.getModifierList().setModifierProperty(PsiModifier.PUBLIC, isPublic);
        }

        PsiMethod fieldMethod = psiElementFactory.createMethodFromText(String.format("%s %s(%s %s);", returnType.getPresentableText(),
                methodName, forMember.getElement().getType().getPresentableText(), paramName), mInterface);

        mInterface.add(fieldMethod);
        return mInterface;
    }

    private PsiMethod generateCopyBuilderMethod(final PsiClass topLevelClass, final PsiType builderType,
                                                final Collection<PsiFieldMember> finalFields,
                                                final Collection<PsiFieldMember> nonFinalfields,
                                                final Set<IntentBuilderOption> options) {
        //create the method
        final PsiMethod copyBuilderMethod = psiElementFactory.createMethod("newBuilder", builderType);
        PsiUtil.setModifierProperty(copyBuilderMethod, PsiModifier.STATIC, true);
        PsiUtil.setModifierProperty(copyBuilderMethod, PsiModifier.PUBLIC, true);

        //add method parameter
        final PsiType topLevelClassType = psiElementFactory.createType(topLevelClass);
        final PsiParameter parameter = psiElementFactory.createParameter("copy", topLevelClassType);
        final PsiModifierList parameterModifierList = parameter.getModifierList();

        copyBuilderMethod.getParameterList().add(parameter);

        //add body to method
        final PsiCodeBlock copyBuilderBody = copyBuilderMethod.getBody();
        if (copyBuilderBody != null) {
            final StringBuilder copyBuilderParameters = new StringBuilder();
            for (final PsiFieldMember fieldMember : finalFields) {
                if (copyBuilderParameters.length() > 0) {
                    copyBuilderParameters.append(", ");
                }

                copyBuilderParameters.append(String.format("copy.%s", fieldMember.getElement().getName()));
            }

            final PsiStatement newBuilderStatement = psiElementFactory.createStatementFromText(String.format(
                    "%s builder = new %s(%s);", builderType.getPresentableText(),
                    builderType.getPresentableText(), copyBuilderParameters.toString()),
                    copyBuilderMethod);
            copyBuilderBody.add(newBuilderStatement);

            addCopyBody(nonFinalfields, copyBuilderMethod, "builder.");
            copyBuilderBody.add(psiElementFactory.createStatementFromText("return builder;", copyBuilderMethod));
        }
        return copyBuilderMethod;
    }

     private void addCopyBody(final Collection<PsiFieldMember> fields, final PsiMethod method, final String qName) {
        final PsiCodeBlock methodBody = method.getBody();
        if (methodBody == null) {
            return;
        }
        for (final PsiFieldMember member : fields) {
            final PsiField field = member.getElement();
            final PsiStatement assignStatement = psiElementFactory.createStatementFromText(String.format(
                    "%s%2$s = copy.%2$s;", qName, field.getName()), method);
            methodBody.add(assignStatement);
        }
    }

    private PsiMethod generateBuilderConstructor(final PsiClass builderClass,
                                                 final Collection<PsiFieldMember> finalFields,
                                                 final Set<IntentBuilderOption> options) {

        final PsiMethod builderConstructor = psiElementFactory.createConstructor(builderClass.getName());
        PsiUtil.setModifierProperty(builderConstructor, PsiModifier.PRIVATE, true);

        final PsiCodeBlock builderConstructorBody = builderConstructor.getBody();
        if (builderConstructorBody != null) {
            for (final PsiFieldMember member : finalFields) {
                final PsiField field = member.getElement();
                final PsiType fieldType = field.getType();
                final String fieldName = field.getName();

                final PsiParameter parameter = psiElementFactory.createParameter(fieldName, fieldType);
                final PsiModifierList parameterModifierList = parameter.getModifierList();

                builderConstructor.getParameterList().add(parameter);
                final PsiStatement assignStatement = psiElementFactory.createStatementFromText(String.format(
                        "this.%1$s = %1$s;", fieldName), builderConstructor);
                builderConstructorBody.add(assignStatement);
            }
        }

        return builderConstructor;
    }

    private PsiMethod generateNewBuilderMethod(final PsiType builderType, final Collection<PsiFieldMember> finalFields,
                                               final Set<IntentBuilderOption> options, final PsiType returnType) {
        final PsiMethod newBuilderMethod = psiElementFactory.createMethod("builder", returnType);
        PsiUtil.setModifierProperty(newBuilderMethod, PsiModifier.STATIC, true);
        PsiUtil.setModifierProperty(newBuilderMethod, PsiModifier.PUBLIC, true);

        final StringBuilder fieldList = new StringBuilder();
        if (!finalFields.isEmpty()) {
            for (final PsiFieldMember member : finalFields) {
                final PsiField field = member.getElement();
                final PsiType fieldType = field.getType();
                final String fieldName = field.getName();

                final PsiParameter parameter = psiElementFactory.createParameter(fieldName, fieldType);
                final PsiModifierList parameterModifierList = parameter.getModifierList();

                newBuilderMethod.getParameterList().add(parameter);
                if (fieldList.length() > 0) {
                    fieldList.append(", ");
                }
                fieldList.append(fieldName);
            }
        }
        final PsiCodeBlock newBuilderMethodBody = newBuilderMethod.getBody();
        if (newBuilderMethodBody != null) {
            final PsiStatement newStatement = psiElementFactory.createStatementFromText(String.format(
                    "return new %s(%s);", builderType.getPresentableText(), fieldList.toString()),
                    newBuilderMethod);
            newBuilderMethodBody.add(newStatement);
        }
        return newBuilderMethod;
    }

    private PsiMethod generateBuilderSetter(final PsiType returnType, final PsiFieldMember member,
                                            final Set<IntentBuilderOption> options) {

        final PsiField field = member.getElement();
        final PsiType fieldType = field.getType();
        final String fieldName = field.getName();

        final String methodName = String.format("with%s", IntentBuilderUtils.capitalize(fieldName));

        final String parameterName = !BUILDER_SETTER_DEFAULT_PARAMETER_NAME.equals(fieldName) ?
                BUILDER_SETTER_DEFAULT_PARAMETER_NAME :
                BUILDER_SETTER_ALTERNATIVE_PARAMETER_NAME;
        final PsiMethod setterMethod = psiElementFactory.createMethod(methodName, returnType);

        setterMethod.getModifierList().addAnnotation(OVERRIDE_ANNOTATION);

        setterMethod.getModifierList().setModifierProperty(PsiModifier.PUBLIC, true);
        final PsiParameter setterParameter = psiElementFactory.createParameter(parameterName, fieldType);

        if (!(fieldType instanceof PsiPrimitiveType)) {
            final PsiModifierList setterParameterModifierList = setterParameter.getModifierList();
        }
        setterMethod.getParameterList().add(setterParameter);
        final PsiCodeBlock setterMethodBody = setterMethod.getBody();
        if (setterMethodBody != null) {
            final PsiStatement assignStatement = psiElementFactory.createStatementFromText(String.format(
                    "%s = %s;", fieldName, parameterName), setterMethod);
            setterMethodBody.add(assignStatement);
            setterMethodBody.add(IntentBuilderUtils.createReturnThis(psiElementFactory, setterMethod));
        }
        return setterMethod;
    }

    private PsiMethod generateConstructor(final PsiClass topLevelClass, final PsiType builderType) {
        final PsiMethod constructor = psiElementFactory.createConstructor(topLevelClass.getName());
        constructor.getModifierList().setModifierProperty(PsiModifier.PRIVATE, true);

        final PsiParameter builderParameter = psiElementFactory.createParameter("intent", builderType);
        constructor.getParameterList().add(builderParameter);

        final PsiCodeBlock constructorBody = constructor.getBody();
        final PsiElement ifStatement = psiElementFactory.createStatementFromText("if (intent != null)", null);
        final PsiCodeBlock bracesCodeBlock = psiElementFactory.createCodeBlock();

        if (constructorBody != null) {
            constructorBody.add(ifStatement);
            for (final PsiFieldMember member : mandatoryFields) {
                final PsiField field = member.getElement();

                final PsiMethod setterPrototype = PropertyUtil.generateSetterPrototype(field);
                final PsiMethod setter = topLevelClass.findMethodBySignature(setterPrototype, true);

                final String fieldName = field.getName();
                boolean isFinal = false;
                final PsiModifierList modifierList = field.getModifierList();
                if (modifierList != null) {
                    isFinal = modifierList.hasModifierProperty(PsiModifier.FINAL);
                }

                final String assignText;
                if (setter == null || isFinal) {
                    assignText = String.format("%s = %s;", fieldName, generateIntentTag(fieldName));
                } else {
                    assignText = String.format("%s(builder.%s);", setter.getName(), fieldName);
                }

                final PsiStatement assignStatement = psiElementFactory.createStatementFromText(assignText, null);
                bracesCodeBlock.add(assignStatement);
            }
            constructorBody.add(bracesCodeBlock);
        }

        return constructor;
    }

    private String generateIntentTag(String fieldNAme) {
        String resp = "null";
        String[] tagData = tagsMap.get(fieldNAme);
        if (tagData != null && tagData[0] != null) {
            switch (tagData[0]) {
                case "boolean":
                    resp = "intent.getBooleanExtra(" + tagData[1] + ", false)";
                    break;
                case "String":
                    resp = "intent.getStringExtra(" + tagData[1] + ")";
                    break;
                case "int":
                    resp = "intent.getIntExtra(" + tagData[1] + ")";
                    break;
                case "long":
                    resp = "intent.getLongExtra(" + tagData[1] + ")";
                    break;
                case "double":
                    resp = "intent.getDoubleExtra(" + tagData[1] + ")";
                    break;
                case "float":
                    resp = "intent.getFloatExtra(" + tagData[1] + ")";
                    break;
                case "char":
                    resp = "intent.getCharExtra(" + tagData[1] + ")";
                    break;
            }
        }

        return resp;
    }

    private PsiMethod generateBuildMethod(final PsiClass topLevelClass, final PsiClass builderClass) {
        final PsiMethod buildMethod = psiElementFactory.createMethod("build", intentType);
        String parameterName = "context";

        PsiParameter builderParameter = psiElementFactory.createParameter(parameterName, contextType);
        buildMethod.getParameterList().add(builderParameter);
        buildMethod.getModifierList().setModifierProperty(PsiModifier.PUBLIC, true);

        final PsiCodeBlock buildMethodBody = buildMethod.getBody();
        if (buildMethodBody != null) {
            final PsiStatement intentStatement = psiElementFactory.createStatementFromText(String.format(
                    "%s %s;", INTENT_CLASS_NAME, "intent"), buildMethod);
            buildMethodBody.add(intentStatement);
            final PsiStatement newIntentStatement = psiElementFactory.createStatementFromText(String.format(
                    "intent = new %s(context, %s.class);", INTENT_CLASS_NAME, topLevelClass.getName()), buildMethod);
            buildMethodBody.add(newIntentStatement);

            for (Map.Entry<String,String[]> entry : tagsMap.entrySet()) {
                final PsiStatement memberStatement = psiElementFactory.createStatementFromText(String.format(
                        "intent.putExtra(%s, %s);", entry.getValue()[1], entry.getKey()), buildMethod);
                buildMethodBody.add(memberStatement);
            }

            final PsiStatement returnStatement = psiElementFactory.createStatementFromText("return intent;", buildMethod);
            buildMethodBody.add(returnStatement);
        }
        return buildMethod;
    }

    private PsiMethod generateGetterMethod(final PsiFieldMember fieldMember, final PsiType type) {
        PsiField field = fieldMember.getElement();
        String fieldName = field.getName();
        String methodName;

        if (TextUtils.isEmpty(fieldName)) {
            return null;
        }

        if (type.getCanonicalText().equals("boolean")) {
            methodName = String.format("is%s", IntentBuilderUtils.capitalize(fieldName));
        } else {
            methodName = String.format("get%s", IntentBuilderUtils.capitalize(fieldName));
        }

        PsiMethod getterMethod = psiElementFactory.createMethod(methodName, type);
        getterMethod.getModifierList().setModifierProperty(PsiModifier.PUBLIC, true);

        final PsiCodeBlock setterMethodBody = getterMethod.getBody();
        if (setterMethodBody != null) {
            setterMethodBody.add(IntentBuilderUtils.createReturn(psiElementFactory, fieldName));
        }

        return getterMethod;
    }

    @NotNull
    private PsiClass findOrCreateBuilderClass(final PsiClass topLevelClass, Collection<PsiClassType> interfaces, PsiClassType interfaceClass) {
        final PsiClass builderClass = topLevelClass.findInnerClassByName(BUILDER_CLASS_NAME, false);
        if (builderClass == null) {
            List<PsiClassType> types = new ArrayList<PsiClassType>(interfaces);
            types.add(interfaceClass);

            return createBuilderClass(topLevelClass, types);
        }

        return builderClass;
    }

    @NotNull
    private PsiClass createBuilderClass(final PsiClass topLevelClass, List<PsiClassType> implementedTypes) {
        final PsiClass builderClass = (PsiClass) topLevelClass.add(psiElementFactory.createClass(BUILDER_CLASS_NAME));
        PsiUtil.setModifierProperty(builderClass, PsiModifier.STATIC, true);
        PsiUtil.setModifierProperty(builderClass, PsiModifier.FINAL, true);

        if(builderClass.getImplementsList() != null) {
            for (PsiClassType type : implementedTypes) {
                builderClass.getImplementsList().add(psiElementFactory.createReferenceElementByType(type));
            }
        }

        return builderClass;
    }

    private PsiElement findOrCreateField(final PsiClass builderClass, final PsiFieldMember member,
                                         @Nullable final PsiElement last) {
        final PsiField field = member.getElement();
        final String fieldName = field.getName();
        final PsiType fieldType = field.getType();
        final PsiField existingField = builderClass.findFieldByName(fieldName, false);
        if (existingField == null || !IntentBuilderUtils.areTypesPresentableEqual(existingField.getType(), fieldType)) {
            if (existingField != null) {
                existingField.delete();
            }
            final PsiField newField = psiElementFactory.createField(fieldName, fieldType);
            if (last != null) {
                return builderClass.addAfter(newField, last);
            } else {
                return builderClass.add(newField);
            }
        }
        return existingField;
    }

    private PsiElement addMethod(@NotNull final PsiClass target, @Nullable final PsiElement after,
                                 @NotNull final PsiMethod newMethod, final boolean replace) {
        PsiMethod existingMethod = target.findMethodBySignature(newMethod, false);
        if (existingMethod == null && newMethod.isConstructor()) {
            for (final PsiMethod constructor : target.getConstructors()) {
                if (IntentBuilderUtils.areParameterListsEqual(constructor.getParameterList(),
                        newMethod.getParameterList())) {
                    existingMethod = constructor;
                    break;
                }
            }
        }
        if (existingMethod == null) {
            if (after != null) {
                return target.addAfter(newMethod, after);
            } else {
                return target.add(newMethod);
            }
        } else if (replace) {
            existingMethod.replace(newMethod);
        }
        return existingMethod;
    }
}

