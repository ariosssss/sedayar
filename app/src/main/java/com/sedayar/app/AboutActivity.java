package com.sedayar.app;

import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.os.Bundle;
import android.view.MenuItem;
import android.view.View;
import android.view.ViewGroup;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.content.ContextCompat;

import com.sedayar.app.databinding.ActivityAboutBinding;
import com.sedayar.app.util.InsetsUtil;

/**
 * درباره — greets the user, lists what the app can do and points to the
 * repository. Reached from the three-lines (☰) menu in the toolbar.
 */
public class AboutActivity extends AppCompatActivity {

    private ActivityAboutBinding binding;

    @Override
    protected void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        binding = ActivityAboutBinding.inflate(getLayoutInflater());
        setContentView(binding.getRoot());
        InsetsUtil.apply(binding.getRoot(), null);

        setSupportActionBar(binding.toolbar);
        if (getSupportActionBar() != null) {
            getSupportActionBar().setDisplayHomeAsUpEnabled(true);
        }

        binding.tvVersion.setText(getString(R.string.about_version) + " "
                + BuildConfig.VERSION_NAME);

        addFeature(R.string.about_f1, R.string.about_f1_desc);
        addFeature(R.string.about_f2, R.string.about_f2_desc);
        addFeature(R.string.about_f3, R.string.about_f3_desc);
        addFeature(R.string.about_f4, R.string.about_f4_desc);
        addFeature(R.string.about_f5, R.string.about_f5_desc);
        addFeature(R.string.about_f6, R.string.about_f6_desc);
        addFeature(R.string.about_f7, R.string.about_f7_desc);
        addFeature(R.string.about_f8, R.string.about_f8_desc);
    }

    /** One golden-dot row: bold feature title over a soft description. */
    private void addFeature(int titleRes, int descRes) {
        int pad = dp(14);
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setPadding(pad, dp(8), pad, dp(8));
        row.setGravity(android.view.Gravity.TOP);

        View dot = new View(this);
        GradientDrawable circle = new GradientDrawable();
        circle.setShape(GradientDrawable.OVAL);
        circle.setColor(ContextCompat.getColor(this, R.color.gold_bright));
        dot.setBackground(circle);
        LinearLayout.LayoutParams dotLp = new LinearLayout.LayoutParams(dp(9), dp(9));
        dotLp.setMargins(0, dp(8), 0, 0);
        row.addView(dot, dotLp);

        LinearLayout textCol = new LinearLayout(this);
        textCol.setOrientation(LinearLayout.VERTICAL);

        TextView title = new TextView(this);
        title.setText(titleRes);
        title.setTextSize(16f);
        title.setTypeface(Typeface.DEFAULT_BOLD);
        title.setTextColor(ContextCompat.getColor(this, R.color.text_primary));

        TextView desc = new TextView(this);
        desc.setText(descRes);
        desc.setTextSize(14f);
        desc.setTextColor(ContextCompat.getColor(this, R.color.text_secondary));
        desc.setLineSpacing(dp(3), 1f);

        textCol.addView(title);
        textCol.addView(desc);
        LinearLayout.LayoutParams colLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT);
        colLp.setMargins(dp(12), 0, 0, 0);
        row.addView(textCol, colLp);

        binding.featureBox.addView(row, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT));
    }

    @Override
    public boolean onOptionsItemSelected(@NonNull MenuItem item) {
        if (item.getItemId() == android.R.id.home) {
            finish();
            return true;
        }
        return super.onOptionsItemSelected(item);
    }

    private int dp(int v) {
        return (int) (v * getResources().getDisplayMetrics().density);
    }
}
