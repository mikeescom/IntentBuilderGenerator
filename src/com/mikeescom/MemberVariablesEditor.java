package com.mikeescom;

import com.intellij.codeInsight.generation.PsiFieldMember;
import com.intellij.openapi.ui.DialogWrapper;
import org.jetbrains.annotations.Nullable;

import javax.swing.*;
import java.awt.*;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

public class MemberVariablesEditor extends DialogWrapper {
    private JTable table;
    private List<PsiFieldMember> listMembers;

    protected MemberVariablesEditor() {
        super(true); // use current window as parent
        init();
    }

    protected MemberVariablesEditor(List<PsiFieldMember> members) {
        super(true); // use current window as parent
        listMembers = members;
        init();
    }

    @Nullable
    @Override
    protected JComponent createCenterPanel() {
        JPanel dialogPanel = new JPanel(new BorderLayout());
        String[] columnNames = {"Variable Name", "TAG"};
        String[][] data = new String[listMembers.size()][2];

        for (int i = 0 ; i < listMembers.size() ; i++) {
            data[i][0] = listMembers.get(i).getText();
        }

        table = new JTable(data, columnNames);
        table.setBounds(30, 40, 200, 300);

        JScrollPane sp = new JScrollPane(table);

        dialogPanel.add(sp, BorderLayout.CENTER);

        return dialogPanel;
    }

    public Map<String, String[]> getTags() {
        Map<String, String[]> tags = new HashMap<>();
        for (int i = 0 ; i < table.getRowCount() ; i++) {
            try {
                String[] data = table.getModel().getValueAt(i, 0).toString().split(":");
                tags.put(data[0], new String[]{data[1], table.getModel().getValueAt(i, 1).toString()});
            } catch (Exception e) {
                e.getMessage();
                continue;
            }
        }
        return tags;
    }

}
