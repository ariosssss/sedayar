package com.sedayar.app;

import android.os.Bundle;

import androidx.appcompat.app.AppCompatActivity;

import com.sedayar.app.databinding.ActivityWelcomeBinding;
import com.sedayar.app.util.AppPrefs;
import com.sedayar.app.util.InsetsUtil;

/**
 * First-launch welcome (خوشامدگویی): a Persian girih-patterned night screen
 * that greets the user and introduces the three core features. Shown only
 * once — MainActivity opens it whenever the "welcomed" flag is unset.
 */
public class WelcomeActivity extends AppCompatActivity {

    private ActivityWelcomeBinding binding;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        binding = ActivityWelcomeBinding.inflate(getLayoutInflater());
        setContentView(binding.getRoot());
        InsetsUtil.apply(binding.getRoot(), null);

        binding.btnStart.setOnClickListener(v -> {
            AppPrefs.setWelcomed(this);
            finish();
        });
    }

    @Override
    public void onBackPressed() {
        // Leaving via back also counts as "seen" so it doesn't nag on relaunch
        AppPrefs.setWelcomed(this);
        super.onBackPressed();
    }
}
