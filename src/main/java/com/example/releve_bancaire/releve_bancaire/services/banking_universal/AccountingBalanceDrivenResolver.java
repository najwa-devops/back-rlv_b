package com.example.releve_bancaire.releve_bancaire.services.banking_universal;

import com.example.releve_bancaire.releve_bancaire.entity.BankTransaction;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.util.List;

@Component
public class AccountingBalanceDrivenResolver implements BalanceDrivenResolver {

    private static final BigDecimal TOLERANCE = new BigDecimal("0.05");

    @Override
    public void resolve(List<BankTransaction> transactions) {
        BigDecimal previousBalance = null;

        for (BankTransaction tx : transactions) {
            if (tx.getFlags() == null) {
                continue;
            }

            if (previousBalance == null) {
                if (tx.getBalance() != null) {
                    previousBalance = tx.getBalance();
                }
                continue;
            }

            BigDecimal expected = previousBalance
                    .add(nullSafe(tx.getCredit()))
                    .subtract(nullSafe(tx.getDebit()));

            if (tx.getBalance() == null) {
                tx.setBalance(expected);
                tx.getFlags().add("BALANCE_INFERRED");
                previousBalance = expected;
                continue;
            }

            if (isClose(tx.getBalance(), expected)) {
                previousBalance = tx.getBalance();
                continue;
            }

            BigDecimal swappedExpected = previousBalance
                    .add(nullSafe(tx.getDebit()))
                    .subtract(nullSafe(tx.getCredit()));
            if (isClose(tx.getBalance(), swappedExpected)) {
                BigDecimal originalDebit = tx.getDebit();
                tx.setDebit(tx.getCredit());
                tx.setCredit(originalDebit);
                tx.setSens(tx.getCredit().compareTo(BigDecimal.ZERO) > 0 ? "CREDIT" : "DEBIT");
                tx.getFlags().add("DEBIT_CREDIT_SWAPPED_BY_BALANCE");
                previousBalance = tx.getBalance();
            } else {
                tx.getFlags().add("BALANCE_MISMATCH");
                previousBalance = tx.getBalance();
            }
        }
    }

    private BigDecimal nullSafe(BigDecimal v) {
        return v == null ? BigDecimal.ZERO : v;
    }

    private boolean isClose(BigDecimal a, BigDecimal b) {
        return a.subtract(b).abs().compareTo(TOLERANCE) <= 0;
    }
}

