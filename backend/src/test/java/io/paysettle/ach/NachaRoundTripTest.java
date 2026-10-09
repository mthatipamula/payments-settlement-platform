package io.paysettle.ach;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.paysettle.ach.nacha.NachaFile;
import io.paysettle.ach.nacha.NachaFileBuilder;
import io.paysettle.ach.nacha.NachaParseException;
import io.paysettle.ach.nacha.NachaParser;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import org.junit.jupiter.api.Test;

class NachaRoundTripTest {

    private static String sample() {
        var payroll = List.of(
                new NachaFileBuilder.OutEntry("22", "021000021", "1234567890", 150000, "EMP001", "Alicia Gomez", "123456780000001", null),
                new NachaFileBuilder.OutEntry("32", "121000248", "9988776655", 99999, "EMP002", "Brian Chen", "123456780000002", "Bonus"));
        var debits = List.of(
                new NachaFileBuilder.OutEntry("27", "026009593", "5555444433", 2599, "SUB9", "Carla Novak", "123456780000003", null));
        return new NachaFileBuilder("011000015", "123456780", "FEDERAL RESERVE BANK", "PAYSETTLE BANK").build(List.of(
                new NachaFileBuilder.OutBatch("ACME PAYROLL", "1123456789", "PPD", "PAYROLL", LocalDate.of(2026, 10, 9), payroll),
                new NachaFileBuilder.OutBatch("ACME PAYROLL", "1123456789", "WEB", "SUBSCRIPT", LocalDate.of(2026, 10, 9), debits)),
                LocalDateTime.of(2026, 10, 9, 9, 30), 'A');
    }

    @Test
    void builtFilesParseWithMatchingControls() {
        String content = sample();
        assertThat(content.lines()).allMatch(l -> l.length() == 94);
        assertThat(content.lines().count() % 10).isZero();

        NachaFile f = NachaParser.parse(content);
        assertThat(f.batches()).hasSize(2);
        assertThat(f.entryCount()).isEqualTo(3);
        assertThat(f.control().totalCreditCents()).isEqualTo(249999);
        assertThat(f.control().totalDebitCents()).isEqualTo(2599);
        assertThat(f.batches().get(0).header().serviceClassCode()).isEqualTo("220");
        assertThat(f.batches().get(1).header().serviceClassCode()).isEqualTo("225");
        NachaFile.Entry bonus = f.batches().get(0).entries().get(1);
        assertThat(bonus.rdfiRouting()).isEqualTo("121000248");
        assertThat(bonus.addenda()).containsExactly("BONUS");
        assertThat(f.batches().get(1).entries().get(0).isDebit()).isTrue();
    }

    @Test
    void rejectsTamperedAmount() {
        String content = sample();
        // Change one entry's amount without fixing the control totals.
        String tampered = content.replaceFirst("(?m)^(6.{28})0000150000", "$10000150001");
        assertThatThrownBy(() -> NachaParser.parse(tampered)).isInstanceOf(NachaParseException.class)
                .hasMessageContaining("total credits");
    }

    @Test
    void rejectsBadCheckDigit() {
        String content = sample();
        String bad = content.replaceFirst("(?m)^(6220210000)2", "$19");
        assertThatThrownBy(() -> NachaParser.parse(bad)).isInstanceOf(NachaParseException.class)
                .hasMessageContaining("check digit");
    }

    @Test
    void rejectsUnblockedFile() {
        String content = sample() + "9".repeat(94) + "\n"; // 11 records: not a multiple of 10
        assertThatThrownBy(() -> NachaParser.parse(content)).isInstanceOf(NachaParseException.class)
                .hasMessageContaining("blocked");
    }
}
