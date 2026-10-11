package in.gov.slate.survey;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.math.BigDecimal;

import org.junit.jupiter.api.Test;

class AreaUnitsTest {

    @Test
    void normalisesUnitLabels() {
        assertThat(AreaUnits.code("Sq.ft")).isEqualTo("SQ_FT");
        assertThat(AreaUnits.code("Acres")).isEqualTo("ACRE");
        assertThat(AreaUnits.code("cents")).isEqualTo("CENT");
        assertThat(AreaUnits.code(" ")).isNull();
    }

    @Test
    void convertsAcresToSquareFeet() {
        assertThat(AreaUnits.convert(new BigDecimal("2.05"), "Acres", "SQ_FT"))
                .isEqualByComparingTo("89298.0000");
    }

    @Test
    void sameUnitIsUnchanged() {
        assertThat(AreaUnits.convert(new BigDecimal("2400"), "SQ_FT", "Sq.ft")).isEqualByComparingTo("2400");
    }

    @Test
    void rejectsUnknownUnits() {
        assertThatThrownBy(() -> AreaUnits.convert(BigDecimal.ONE, "GROUND", "SQ_FT"))
                .hasMessageContaining("Cannot compare");
    }
}
