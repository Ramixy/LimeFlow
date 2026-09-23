package io.github.dovecoteescapee.byedpi

import io.github.dovecoteescapee.byedpi.data.StrategyMemory
import org.junit.Assert.assertEquals
import org.junit.Test

class StrategyNetworkTest {
    @Test fun operatorNamesAreGroupedWithoutPermissions() {
        assertEquals("МегаФон", StrategyMemory.normalizeOperator("Megafon"))
        assertEquals("Билайн", StrategyMemory.normalizeOperator("Beeline"))
        assertEquals("МТС", StrategyMemory.normalizeOperator("MTS"))
        assertEquals("оператор не определён", StrategyMemory.normalizeOperator(""))
        assertEquals("strategy_test_results_v4_mobile_мтс", StrategyMemory.resultsKey("mobile_мтс"))
        assertEquals(StrategyMemory.RESULTS_KEY, StrategyMemory.resultsKey("legacy"))
    }
}
