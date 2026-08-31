package org.codezaiku;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;

/**
 * A flag typed without its value must fail loudly.
 *
 * <p>Reported from a real first run: {@code codezaiku chat --drive} with nothing after it quietly
 * meant "use localhost:8200", so an incomplete command looked like it had worked and then failed
 * against a server the user had never asked for.
 */
class ArgFlagTest {

    @Test
    void aPresentFlagYieldsItsValue() {
        assertThat(FamiliarMain.flag(new String[]{"chat", "--drive", "http://x"}, "--drive", "d"))
                .isEqualTo("http://x");
    }

    @Test
    void anAbsentFlagYieldsTheDefault() {
        assertThat(FamiliarMain.flag(new String[]{"chat"}, "--drive", "d")).isEqualTo("d");
    }

    @Test
    void aFlagAtTheEndWithNoValueIsAnError() {
        assertThatThrownBy(() -> FamiliarMain.flag(new String[]{"chat", "--drive"}, "--drive", "d"))
                .hasMessageContaining("needs a value");
    }

    @Test
    void theNextFlagIsNotAValue() {
        assertThatThrownBy(() ->
                FamiliarMain.flag(new String[]{"chat", "--drive", "--mode", "ask"}, "--drive", "d"))
                .hasMessageContaining("needs a value");
    }
}
