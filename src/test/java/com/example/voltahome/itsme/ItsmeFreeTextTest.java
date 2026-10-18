package com.example.voltahome.itsme;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;

/** README: "Confirmation of an operation". The text shown in the itsme app is exactly what the application built. */
class ItsmeFreeTextTest {

    @Test
    void onlyTheTagsItsmeRendersAreEmitted() {
        ItsmeFreeText text = ItsmeFreeText.builder().bold("Title").lineBreak().text("Amount: EUR 85.00").build();

        assertThat(text.value()).isEqualTo("<b>Title</b><br>Amount: EUR 85.00");
    }

    @Test
    void markupInTheTextIsRefused() {
        assertThatThrownBy(() -> ItsmeFreeText.builder().text("EUR 85 <i>or more</i>"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> ItsmeFreeText.builder().bold("Martin &amp; Peeters"))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void textLongerThanItsmeAcceptsIsRefused() {
        assertThatThrownBy(() -> ItsmeFreeText.builder().text("x".repeat(ItsmeFreeText.MAX_LENGTH + 1)).build())
                .isInstanceOf(IllegalArgumentException.class);
        assertThat(ItsmeFreeText.builder().text("x".repeat(ItsmeFreeText.MAX_LENGTH)).build().value())
                .hasSize(ItsmeFreeText.MAX_LENGTH);
    }

    @Test
    void charactersOutsideIso885915AreRefused() {
        assertThat(ItsmeFreeText.builder().text("Avance mensuelle : 85,00 € (révisée)").build().value())
                .contains("€");
        assertThatThrownBy(() -> ItsmeFreeText.builder().text("Confirmed ✓").build())
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void emptyTextIsRefused() {
        assertThatThrownBy(() -> ItsmeFreeText.builder().build()).isInstanceOf(IllegalArgumentException.class);
    }
}
