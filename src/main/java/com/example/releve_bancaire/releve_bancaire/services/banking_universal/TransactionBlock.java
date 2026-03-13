package com.example.releve_bancaire.releve_bancaire.services.banking_universal;

import java.util.ArrayList;
import java.util.List;

public class TransactionBlock {
    private final List<String> lines = new ArrayList<>();
    private final int startLineNumber;

    public TransactionBlock(int startLineNumber) {
        this.startLineNumber = startLineNumber;
    }

    public void addLine(String line) {
        if (line != null && !line.isBlank()) {
            lines.add(line.trim());
        }
    }

    public List<String> getLines() {
        return lines;
    }

    public int getStartLineNumber() {
        return startLineNumber;
    }

    public String joinedText() {
        return String.join(" ", lines);
    }
}

