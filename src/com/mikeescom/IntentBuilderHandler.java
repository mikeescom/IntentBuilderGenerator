package com.mikeescom;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import org.jetbrains.annotations.NotNull;

import com.intellij.codeInsight.CodeInsightUtilBase;
import com.intellij.codeInsight.generation.PsiFieldMember;

import com.intellij.lang.LanguageCodeInsightActionHandler;

import com.intellij.openapi.editor.Document;
import com.intellij.openapi.editor.Editor;
import com.intellij.openapi.fileEditor.FileDocumentManager;
import com.intellij.openapi.project.Project;

import com.intellij.psi.PsiDocumentManager;
import com.intellij.psi.PsiFile;
import com.intellij.psi.PsiJavaFile;

import static com.mikeescom.IntentBuilderCollector.collectFields;
import static com.mikeescom.IntentBuilderTagEditor.getFields;
import static com.mikeescom.IntentBuilderTagEditor.getTags;

public class IntentBuilderHandler  implements LanguageCodeInsightActionHandler {

    private static boolean isApplicable(final PsiFile file, final Editor editor) {
        final List<PsiFieldMember> targetElements = collectFields(file, editor);
        return targetElements != null && !targetElements.isEmpty();
    }

    @Override
    public boolean isValidFor(final Editor editor, final PsiFile file) {
        if (!(file instanceof PsiJavaFile)) {
            return false;
        }

        final Project project = editor.getProject();
        if (project == null) {
            return false;
        }

        return IntentBuilderUtils.getTopLevelClass(project, file, editor) != null && isApplicable(file, editor);
    }

    @Override
    public boolean startInWriteAction() {
        return false;
    }

    @Override
    public void invoke(@NotNull final Project project, @NotNull final Editor editor, @NotNull final PsiFile file) {
        final PsiDocumentManager psiDocumentManager = PsiDocumentManager.getInstance(project);
        final Document currentDocument = psiDocumentManager.getDocument(file);
        if (currentDocument == null) {
            return;
        }

        psiDocumentManager.commitDocument(currentDocument);

        if (!CodeInsightUtilBase.prepareEditorForWrite(editor)) {
            return;
        }

        if (!FileDocumentManager.getInstance().requestWriting(editor.getDocument(), project)) {
            return;
        }

        final List<PsiFieldMember> existingFields = collectFields(file, editor);
        if (existingFields != null) {
            final List<PsiFieldMember> fields = getFields(existingFields, project);
            final Map<String, String[]> tags = getTags();

            if (fields == null) {
                return;
            } else {
                IntentBuilderGenerator.generate(project, editor, file, fields, tags);
            }
        }
    }

}