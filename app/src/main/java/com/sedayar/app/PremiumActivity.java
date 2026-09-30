package com.sedayar.app;

import android.graphics.drawable.GradientDrawable;
import android.os.Bundle;
import android.view.View;
import android.widget.Toast;

import androidx.appcompat.app.AppCompatActivity;
import androidx.core.content.ContextCompat;

import com.google.android.material.card.MaterialCardView;
import com.google.android.material.dialog.MaterialAlertDialogBuilder;
import com.sedayar.app.databinding.ActivityPremiumBinding;
import com.sedayar.app.util.PremiumManager;

/**
 * Sedayar Plus subscription screen.
 *
 * PAYMENT GATEWAY INTEGRATION POINT
 * ---------------------------------
 * Add your billing SDK (Cafe Bazaar In-App Billing / Myket IAB / Zarinpal ...)
 * and set {@link #purchaseListener} before the screen opens. When the user taps
 * the purchase button, onPlanSelected(planId) fires with one of:
 *   PremiumManager.PLAN_MONTHLY / PLAN_YEARLY / PLAN_LIFETIME
 * Verify the payment there, then call:
 *   PremiumManager.setPremium(context, true, planId);
 */
public class PremiumActivity extends AppCompatActivity {

    public interface PurchaseListener {
        void onPlanSelected(String planId);
    }

    public static volatile PurchaseListener purchaseListener;

    private ActivityPremiumBinding binding;
    private String selectedPlan = PremiumManager.PLAN_YEARLY;
    private int devTaps = 0;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        binding = ActivityPremiumBinding.inflate(getLayoutInflater());
        setContentView(binding.getRoot());

        binding.toolbar.setNavigationIcon(R.drawable.ic_back);
        binding.toolbar.setNavigationOnClickListener(v -> finish());

        binding.planMonthly.setOnClickListener(v -> select(PremiumManager.PLAN_MONTHLY));
        binding.planYearly.setOnClickListener(v -> select(PremiumManager.PLAN_YEARLY));
        binding.planLifetime.setOnClickListener(v -> select(PremiumManager.PLAN_LIFETIME));
        select(selectedPlan);

        binding.btnPurchase.setOnClickListener(v -> onPurchaseClicked());

        // Hidden test unlock: tap the version text 7 times
        binding.tvVersion.setOnClickListener(v -> {
            devTaps++;
            if (devTaps >= 7) {
                devTaps = 0;
                boolean active = !PremiumManager.isPremium(this);
                PremiumManager.setPremium(this, active, active ? selectedPlan : null);
                Toast.makeText(this, R.string.dev_unlock_toast, Toast.LENGTH_SHORT).show();
                refreshStatus();
            }
        });

        refreshStatus();
    }

    private void select(String plan) {
        selectedPlan = plan;
        highlight(binding.planMonthly,
                PremiumManager.PLAN_MONTHLY.equals(plan), binding.radioMonthly);
        highlight(binding.planYearly,
                PremiumManager.PLAN_YEARLY.equals(plan), binding.radioYearly);
        highlight(binding.planLifetime,
                PremiumManager.PLAN_LIFETIME.equals(plan), binding.radioLifetime);
    }

    private void highlight(MaterialCardView card, boolean selected, View radio) {
        card.setStrokeWidth(dp(selected ? 2 : 1));
        card.setStrokeColor(ContextCompat.getColor(this,
                selected ? R.color.primary : R.color.stroke));

        GradientDrawable d = new GradientDrawable();
        d.setShape(GradientDrawable.OVAL);
        d.setColor(ContextCompat.getColor(this,
                selected ? R.color.primary : android.R.color.transparent));
        d.setStroke(dp(2), ContextCompat.getColor(this,
                selected ? R.color.primary : R.color.stroke));
        radio.setBackground(d);
    }

    private void onPurchaseClicked() {
        if (PremiumManager.isPremium(this)) {
            Toast.makeText(this, R.string.premium_active_btn, Toast.LENGTH_SHORT).show();
            return;
        }
        if (purchaseListener != null) {
            purchaseListener.onPlanSelected(selectedPlan);
        } else {
            new MaterialAlertDialogBuilder(this)
                    .setTitle(R.string.soon_title)
                    .setMessage(R.string.soon_msg)
                    .setPositiveButton(R.string.ok, null)
                    .show();
        }
    }

    private void refreshStatus() {
        boolean active = PremiumManager.isPremium(this);
        binding.tvStatus.setText(active ? R.string.status_active : R.string.status_inactive);
        binding.btnPurchase.setText(active
                ? R.string.premium_active_btn
                : R.string.cta_activate);
    }

    private int dp(int v) {
        return (int) (v * getResources().getDisplayMetrics().density);
    }
}
