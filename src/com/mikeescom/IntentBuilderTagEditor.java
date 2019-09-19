package com.mikeescom;

import com.intellij.codeInsight.generation.PsiFieldMember;
import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.project.Project;
import org.jetbrains.annotations.Nullable;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

public class IntentBuilderTagEditor {
    private static Map<String, String[]> tags = new HashMap<>();

    private IntentBuilderTagEditor() {
    }

    @Nullable
    public static List<PsiFieldMember> getFields(final List<PsiFieldMember> members,
                                                              final Project project) {
        if (members == null || members.isEmpty()) {
            return null;
        }

        if (ApplicationManager.getApplication().isUnitTestMode()) {
            return members;
        }

        MemberVariablesEditor chooser = new MemberVariablesEditor(members);
        chooser.setTitle("Enter variable tags and Options for the Builder");
        if (chooser.showAndGet()) {
            tags = chooser.getTags();
        }

        return members;
    }

    public static Map<String, String[]> getTags() {
        return tags;
    }
}
