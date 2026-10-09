package io.paysettle.wire.iso;

import com.prowidesoftware.swift.model.mx.MxPacs00200110;
import com.prowidesoftware.swift.model.mx.MxPacs00800108;
import com.prowidesoftware.swift.model.mx.MxWriteParams;
import com.prowidesoftware.swift.model.mx.dic.AccountIdentification4Choice;
import com.prowidesoftware.swift.model.mx.dic.ActiveCurrencyAndAmount;
import com.prowidesoftware.swift.model.mx.dic.BranchAndFinancialInstitutionIdentification6;
import com.prowidesoftware.swift.model.mx.dic.CashAccount38;
import com.prowidesoftware.swift.model.mx.dic.ChargeBearerType1Code;
import com.prowidesoftware.swift.model.mx.dic.ClearingSystemIdentification2Choice;
import com.prowidesoftware.swift.model.mx.dic.ClearingSystemIdentification3Choice;
import com.prowidesoftware.swift.model.mx.dic.ClearingSystemMemberIdentification2;
import com.prowidesoftware.swift.model.mx.dic.CreditTransferTransaction39;
import com.prowidesoftware.swift.model.mx.dic.FIToFICustomerCreditTransferV08;
import com.prowidesoftware.swift.model.mx.dic.FIToFIPaymentStatusReportV10;
import com.prowidesoftware.swift.model.mx.dic.FinancialInstitutionIdentification18;
import com.prowidesoftware.swift.model.mx.dic.GenericAccountIdentification1;
import com.prowidesoftware.swift.model.mx.dic.GroupHeader91;
import com.prowidesoftware.swift.model.mx.dic.GroupHeader93;
import com.prowidesoftware.swift.model.mx.dic.PartyIdentification135;
import com.prowidesoftware.swift.model.mx.dic.PaymentIdentification7;
import com.prowidesoftware.swift.model.mx.dic.PaymentTransaction110;
import com.prowidesoftware.swift.model.mx.dic.RemittanceInformation16;
import com.prowidesoftware.swift.model.mx.dic.SettlementInstruction7;
import com.prowidesoftware.swift.model.mx.dic.SettlementMethod1Code;
import com.prowidesoftware.swift.model.mx.dic.StatusReason6Choice;
import com.prowidesoftware.swift.model.mx.dic.StatusReasonInformation12;
import io.paysettle.common.ApiException;
import io.paysettle.wire.WireInstruction;
import java.time.OffsetDateTime;
import java.util.UUID;
import org.springframework.stereotype.Component;

/**
 * Maps wires to and from ISO 20022 using the Prowide model:
 * <ul>
 *   <li>pacs.008.001.08 FI-to-FI customer credit transfer (Fedwire Funds ISO 20022 format since March 2025),
 *       clearing system FDW, agents identified by ABA routing number (USABA), settlement method CLRG</li>
 *   <li>pacs.002.001.10 payment status report (ACSC accepted and settled / RJCT rejected)</li>
 * </ul>
 * Fedwire also wraps messages in a Business Application Header (head.001); that envelope belongs to the
 * FedLine transport adapter and is not modelled here.
 */
@Component
public class Iso20022Mapper {

    public String toPacs008(WireInstruction w, OffsetDateTime createdAt) {
        CreditTransferTransaction39 tx = new CreditTransferTransaction39()
                .setPmtId(new PaymentIdentification7().setInstrId(w.instructionId()).setEndToEndId(w.endToEndId())
                        .setUETR(w.uetr().toString()))
                .setIntrBkSttlmAmt(new ActiveCurrencyAndAmount().setCcy(w.currency()).setValue(w.amount()))
                .setIntrBkSttlmDt(w.valueDate())
                .setChrgBr(ChargeBearerType1Code.SLEV)
                .setInstgAgt(agent(w.debtorAgentAba()))
                .setInstdAgt(agent(w.creditorAgentAba()))
                .setDbtr(new PartyIdentification135().setNm(w.debtorName()))
                .setDbtrAcct(account(w.debtorAccount()))
                .setDbtrAgt(agent(w.debtorAgentAba()))
                .setCdtrAgt(agent(w.creditorAgentAba()))
                .setCdtr(new PartyIdentification135().setNm(w.creditorName()))
                .setCdtrAcct(account(w.creditorAccount()));
        if (w.remittanceInfo() != null && !w.remittanceInfo().isBlank()) {
            tx.setRmtInf(new RemittanceInformation16().addUstrd(w.remittanceInfo()));
        }
        FIToFICustomerCreditTransferV08 doc = new FIToFICustomerCreditTransferV08()
                .setGrpHdr(new GroupHeader93().setMsgId(w.msgId()).setCreDtTm(createdAt).setNbOfTxs("1")
                        .setSttlmInf(new SettlementInstruction7().setSttlmMtd(SettlementMethod1Code.CLRG)
                                .setClrSys(new ClearingSystemIdentification3Choice().setCd("FDW"))));
        doc.getCdtTrfTxInf().add(tx);
        return new MxPacs00800108().setFIToFICstmrCdtTrf(doc).document(writeParams());
    }

    /** Parses an inbound pacs.008 (one transaction per message, as Fedwire requires). */
    public WireInstruction fromPacs008(String xml) {
        MxPacs00800108 mx;
        try {
            mx = MxPacs00800108.parse(xml);
        } catch (RuntimeException e) {
            throw ApiException.unprocessable("INVALID_ISO20022", "Not a parseable pacs.008.001.08 message");
        }
        if (mx == null || mx.getFIToFICstmrCdtTrf() == null || mx.getFIToFICstmrCdtTrf().getCdtTrfTxInf().size() != 1) {
            throw ApiException.unprocessable("INVALID_ISO20022", "pacs.008 must contain exactly one CdtTrfTxInf");
        }
        var hdr = mx.getFIToFICstmrCdtTrf().getGrpHdr();
        CreditTransferTransaction39 tx = mx.getFIToFICstmrCdtTrf().getCdtTrfTxInf().getFirst();
        try {
            String uetr = tx.getPmtId().getUETR();
            return new WireInstruction(uetr == null ? UUID.randomUUID() : UUID.fromString(uetr), hdr.getMsgId(),
                    tx.getPmtId().getInstrId(), tx.getPmtId().getEndToEndId(), tx.getIntrBkSttlmAmt().getValue(),
                    tx.getIntrBkSttlmAmt().getCcy(), tx.getIntrBkSttlmDt(), tx.getDbtr().getNm(),
                    tx.getDbtrAcct().getId().getOthr().getId(), aba(tx.getDbtrAgt()), tx.getCdtr().getNm(),
                    tx.getCdtrAcct().getId().getOthr().getId(), aba(tx.getCdtrAgt()),
                    tx.getRmtInf() == null || tx.getRmtInf().getUstrd().isEmpty() ? null : tx.getRmtInf().getUstrd().getFirst());
        } catch (NullPointerException | IllegalArgumentException e) {
            throw ApiException.unprocessable("INVALID_ISO20022",
                    "pacs.008 is missing a required element (UETR, amount, debtor/creditor, accounts or USABA agents)");
        }
    }

    /** pacs.002 status report for a wire: ACSC (accepted, settlement completed) or RJCT with a reason code. */
    public String toPacs002(WireInstruction w, String status, String reasonCode, String reasonText, OffsetDateTime at) {
        PaymentTransaction110 tx = new PaymentTransaction110().setOrgnlInstrId(w.instructionId())
                .setOrgnlEndToEndId(w.endToEndId()).setOrgnlUETR(w.uetr().toString()).setTxSts(status).setAccptncDtTm(at);
        if (reasonCode != null) {
            tx.addStsRsnInf(new StatusReasonInformation12().setRsn(new StatusReason6Choice().setCd(reasonCode))
                    .addAddtlInf(reasonText == null ? "" : reasonText.substring(0, Math.min(105, reasonText.length()))));
        }
        FIToFIPaymentStatusReportV10 report = new FIToFIPaymentStatusReportV10()
                .setGrpHdr(new GroupHeader91().setMsgId("STS" + UUID.randomUUID().toString().replace("-", "").substring(0, 29))
                        .setCreDtTm(at));
        report.addTxInfAndSts(tx);
        return new MxPacs00200110().setFIToFIPmtStsRpt(report).document(writeParams());
    }

    /** Default namespace (no "doc:" prefix), as in the Fedwire ISO 20022 samples. */
    private static MxWriteParams writeParams() {
        MxWriteParams params = new MxWriteParams();
        params.prefix = null;
        params.includeXMLDeclaration = true;
        return params;
    }

    private static BranchAndFinancialInstitutionIdentification6 agent(String aba) {
        return new BranchAndFinancialInstitutionIdentification6().setFinInstnId(new FinancialInstitutionIdentification18()
                .setClrSysMmbId(new ClearingSystemMemberIdentification2()
                        .setClrSysId(new ClearingSystemIdentification2Choice().setCd("USABA")).setMmbId(aba)));
    }

    private static CashAccount38 account(String number) {
        return new CashAccount38().setId(new AccountIdentification4Choice()
                .setOthr(new GenericAccountIdentification1().setId(number)));
    }

    private static String aba(BranchAndFinancialInstitutionIdentification6 agent) {
        return agent.getFinInstnId().getClrSysMmbId().getMmbId();
    }
}
