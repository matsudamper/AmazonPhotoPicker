package net.matsudamper.amazonphotopicker

import net.matsudamper.amazonphotopicker.NavigationPolicy.Decision
import org.junit.Assert.assertEquals
import org.junit.Test

class NavigationPolicyTest {
    @Test
    fun httpIsAllowed() {
        assertEquals(Decision.Allow, NavigationPolicy.decide("https://www.amazon.co.jp/photos/"))
    }

    @Test
    fun intentWithoutFallbackIsBlocked() {
        assertEquals(
            Decision.Block,
            NavigationPolicy.decide("intent://details?id=com.amazon.clouddrive.photos#Intent;scheme=market;package=com.android.vending;end"),
        )
    }

    @Test
    fun intentWithHttpFallbackIsRedirected() {
        assertEquals(
            Decision.Redirect("https://www.amazon.co.jp/photos/all?x=1"),
            NavigationPolicy.decide(
                "intent://details?id=foo#Intent;scheme=market;S.browser_fallback_url=https%3A%2F%2Fwww.amazon.co.jp%2Fphotos%2Fall%3Fx%3D1;end",
            ),
        )
    }

    @Test
    fun intentWithNonHttpFallbackIsBlocked() {
        assertEquals(
            Decision.Block,
            NavigationPolicy.decide("intent://x#Intent;S.browser_fallback_url=market%3A%2F%2Fdetails;end"),
        )
    }

    @Test
    fun otherSchemesAreBlocked() {
        assertEquals(Decision.Block, NavigationPolicy.decide("market://details?id=foo"))
        assertEquals(Decision.Block, NavigationPolicy.decide("amazonphotos://open"))
    }
}
