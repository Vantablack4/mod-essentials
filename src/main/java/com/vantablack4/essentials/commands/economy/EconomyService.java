package com.vantablack4.essentials.commands.economy;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.players.NameAndId;

final class EconomyService {
    private final EconomySettings settings;
    private final EconomyStore store;
    private final Map<UUID, PendingPayment> pendingPayments = new HashMap<>();

    EconomyService(EconomySettings settings, EconomyStore store) {
        this.settings = settings;
        this.store = store;
    }

    EconomySettings settings() {
        return settings;
    }

    EconomyStore store() {
        return store;
    }

    EconomyAccount account(ServerPlayer player) {
        return store.touch(player, settings.startingBalance());
    }

    Optional<EconomyAccount> resolveAccount(MinecraftServer server, String rawTarget) {
        Optional<ServerPlayer> online = EconomyPlayerLookup.findOnline(server, rawTarget);
        if (online.isPresent()) {
            return Optional.of(account(online.get()));
        }
        Optional<EconomyAccount> stored = store.findAccount(rawTarget);
        if (stored.isPresent()) {
            return stored;
        }
        return EconomyPlayerLookup.findKnownProfile(server, rawTarget)
            .map(profile -> store.touch(profile.id(), profile.name(), profile.name(), settings.startingBalance()));
    }

    TransferResult pay(ServerPlayer payerPlayer, ServerPlayer receiverPlayer, BigDecimal amount, String commandText) {
        EconomyAccount payer = account(payerPlayer);
        EconomyAccount receiver = account(receiverPlayer);
        if (payer.uuid().equals(receiver.uuid())) {
            return new TransferResult(TransferStatus.SELF, payer, receiver, amount);
        }
        if (amount.compareTo(settings.minimumPayAmount()) < 0) {
            return new TransferResult(TransferStatus.BELOW_MINIMUM_PAY, payer, receiver, amount);
        }
        if (!receiver.acceptingPay()) {
            return new TransferResult(TransferStatus.RECEIVER_NOT_ACCEPTING, payer, receiver, amount);
        }
        BigDecimal payerAfter = payer.balance().subtract(amount);
        BigDecimal receiverAfter = receiver.balance().add(amount);
        if (payerAfter.signum() < 0) {
            return new TransferResult(TransferStatus.INSUFFICIENT_FUNDS, payer, receiver, amount);
        }
        if (settings.wouldExceedMax(receiverAfter)) {
            return new TransferResult(TransferStatus.RECEIVER_WOULD_EXCEED_MAX, payer, receiver, amount);
        }

        if (payer.confirmingPayments() && !hasConfirmedPayment(payer.uuid(), receiver.uuid(), amount)) {
            pendingPayments.put(payer.uuid(), new PendingPayment(receiver.uuid(), amount, commandText, Instant.now().plus(settings.paymentConfirmationTimeout())));
            return new TransferResult(TransferStatus.CONFIRMATION_REQUIRED, payer, receiver, amount);
        }

        pendingPayments.remove(payer.uuid());
        EconomyAccount updatedPayer = store.setBalance(payer.uuid(), payerAfter);
        EconomyAccount updatedReceiver = store.setBalance(receiver.uuid(), receiverAfter);
        return new TransferResult(TransferStatus.SUCCESS, updatedPayer, updatedReceiver, amount);
    }

    EcoResult eco(EconomyAccount target, EcoOperation operation, BigDecimal amount) {
        BigDecimal current = target.balance();
        BigDecimal next = switch (operation) {
            case GIVE -> current.add(amount);
            case TAKE -> current.subtract(amount);
            case SET -> settings.clamp(amount);
            case RESET -> settings.clamp(settings.startingBalance());
        };
        if (operation == EcoOperation.TAKE && settings.wouldFallBelowMin(next)) {
            return new EcoResult(EcoStatus.BELOW_MINIMUM, target, current, settings.minMoney());
        }
        if (operation == EcoOperation.GIVE && settings.wouldExceedMax(next)) {
            return new EcoResult(EcoStatus.ABOVE_MAXIMUM, target, current, settings.maxMoney());
        }
        EconomyAccount updated = store.setBalance(target.uuid(), settings.clamp(next));
        return new EcoResult(EcoStatus.SUCCESS, updated, current, updated.balance());
    }

    EconomyAccount setAcceptingPay(EconomyAccount account, boolean enabled) {
        return store.setAcceptingPay(account.uuid(), enabled);
    }

    EconomyAccount toggleAcceptingPay(EconomyAccount account) {
        return setAcceptingPay(account, !account.acceptingPay());
    }

    EconomyAccount setConfirmingPayments(EconomyAccount account, boolean enabled) {
        pendingPayments.remove(account.uuid());
        return store.setConfirmingPayments(account.uuid(), enabled);
    }

    EconomyAccount toggleConfirmingPayments(EconomyAccount account) {
        return setConfirmingPayments(account, !account.confirmingPayments());
    }

    List<EconomyAccount> topBalances(int page) {
        int offset = Math.max(0, page - 1) * settings.balanceTopPageSize();
        return store.accounts().stream()
            .filter(account -> settings.showZeroBalanceTop() || account.balance().signum() > 0)
            .sorted(Comparator.comparing(EconomyAccount::balance).reversed().thenComparing(EconomyAccount::accountName, String.CASE_INSENSITIVE_ORDER))
            .skip(offset)
            .limit(settings.balanceTopPageSize())
            .toList();
    }

    private boolean hasConfirmedPayment(UUID payer, UUID receiver, BigDecimal amount) {
        PendingPayment pending = pendingPayments.get(payer);
        if (pending == null || pending.expiresAt().isBefore(Instant.now())) {
            pendingPayments.remove(payer);
            return false;
        }
        return pending.receiver().equals(receiver) && pending.amount().compareTo(amount) == 0;
    }

    record TransferResult(TransferStatus status, EconomyAccount payer, EconomyAccount receiver, BigDecimal amount) {
    }

    enum TransferStatus {
        SUCCESS,
        CONFIRMATION_REQUIRED,
        BELOW_MINIMUM_PAY,
        INSUFFICIENT_FUNDS,
        RECEIVER_NOT_ACCEPTING,
        RECEIVER_WOULD_EXCEED_MAX,
        SELF
    }

    record EcoResult(EcoStatus status, EconomyAccount account, BigDecimal previousBalance, BigDecimal resultingBalance) {
    }

    enum EcoStatus {
        SUCCESS,
        BELOW_MINIMUM,
        ABOVE_MAXIMUM
    }

    enum EcoOperation {
        GIVE,
        TAKE,
        SET,
        RESET
    }

    private record PendingPayment(UUID receiver, BigDecimal amount, String commandText, Instant expiresAt) {
    }
}
