package com.sedayar.app.util;

import android.content.Context;
import android.content.SharedPreferences;

/**
 * Premium (Sedayar Plus) entitlement store.
 *
 * HOW TO CONNECT A PAYMENT GATEWAY LATER
 * --------------------------------------
 * 1. Add the store/billing SDK (e.g. Cafe Bazaar In-App Billing, Myket IAB,
 *    or Zarinpal SDK) to app/build.gradle.
 * 2. In PremiumActivity, set its static `purchaseListener` and start the
 *    purchase flow when {@link com.sedayar.app.PremiumActivity.PurchaseListener#onPlanSelected(String)}
 *    fires with one of the plan IDs below.
 * 3. After the gateway verifies the payment, call {@link #setPremium(Context, boolean, String)}
 *    to unlock premium. Everything else in the app already reads from here.
 */
public final class PremiumManager {

    public static final String PLAN_MONTHLY = "sedayar_plus_monthly";
    public static final String PLAN_YEARLY = "sedayar_plus_yearly";
    public static final String PLAN_LIFETIME = "sedayar_plus_lifetime";

    private static final String PREFS = "sedayar_prefs";
    private static final String KEY_PREMIUM = "premium_active";
    private static final String KEY_PLAN = "premium_plan";

    private PremiumManager() {
    }

    private static SharedPreferences prefs(Context c) {
        return c.getApplicationContext().getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    public static boolean isPremium(Context c) {
        return prefs(c).getBoolean(KEY_PREMIUM, false);
    }

    public static String plan(Context c) {
        return prefs(c).getString(KEY_PLAN, null);
    }

    /**
     * Called by the payment SDK integration once a purchase is verified.
     */
    public static void setPremium(Context c, boolean active, String planId) {
        SharedPreferences.Editor e = prefs(c).edit().putBoolean(KEY_PREMIUM, active);
        if (planId != null) {
            e.putString(KEY_PLAN, planId);
        } else {
            e.remove(KEY_PLAN);
        }
        e.apply();
    }
}
