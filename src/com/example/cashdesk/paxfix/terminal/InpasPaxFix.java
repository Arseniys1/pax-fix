package com.example.cashdesk.paxfix.terminal;

import com.bifit.cashdesk.desktop.common.terminal.OnInteraction;
import com.bifit.cashdesk.desktop.common.terminal.PaymentParameters;
import com.bifit.cashdesk.desktop.common.terminal.TransactionInfo;
import com.bifit.cashdesk.desktop.plugin.terminal.inpas.InpasTerminalProtocol;
import com.example.cashdesk.paxfix.shared.PaxSlipRegistry;

import java.util.List;
import java.util.Map;

/**
 * Thin bridge that delegates every terminal operation to the stock INPAS
 * implementation and only registers hashes of returned slips for RR Electro.
 */
public final class InpasPaxFix extends InpasTerminalProtocol {

    @Override
    public TransactionInfo payment(PaymentParameters parameters, OnInteraction interaction) {
        return register(super.payment(parameters, interaction));
    }

    @Override
    public TransactionInfo reverse(
            PaymentParameters parameters,
            TransactionInfo transaction,
            OnInteraction interaction) {
        return register(super.reverse(parameters, transaction, interaction));
    }

    @Override
    public TransactionInfo abort(
            PaymentParameters parameters,
            TransactionInfo transaction,
            OnInteraction interaction) {
        return register(super.abort(parameters, transaction, interaction));
    }

    @Override
    public TransactionInfo commit(
            PaymentParameters parameters,
            TransactionInfo transaction,
            OnInteraction interaction) {
        return register(super.commit(parameters, transaction, interaction));
    }

    @Override
    public TransactionInfo closeDay() {
        return register(super.closeDay());
    }

    @Override
    public TransactionInfo report(String report, Map<String, String> parameters) {
        return register(super.report(report, parameters));
    }

    private static TransactionInfo register(TransactionInfo transaction) {
        if (transaction == null) {
            return null;
        }

        List<String> slips = transaction.getSlips();
        if (slips == null || slips.isEmpty()) {
            return transaction;
        }

        for (String slip : slips) {
            PaxSlipRegistry.registerSlip(slip);
        }
        return transaction;
    }
}

