package com.vantablack4.essentials.commands.economy;

import java.math.BigDecimal;
import java.util.UUID;

record EconomyAccount(
    UUID uuid,
    String accountName,
    String displayName,
    BigDecimal balance,
    boolean acceptingPay,
    boolean confirmingPayments
) {
    String listedName() {
        if (displayName == null || displayName.isBlank() || displayName.equals(accountName)) {
            return accountName;
        }
        return displayName + " (" + accountName + ")";
    }
}
