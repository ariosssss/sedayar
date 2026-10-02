package com.sedayar.app;

import android.content.Intent;
import android.os.Bundle;
import android.view.Menu;
import android.view.MenuItem;

import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;
import androidx.appcompat.widget.SearchView;
import androidx.fragment.app.Fragment;
import androidx.fragment.app.FragmentManager;
import androidx.fragment.app.FragmentTransaction;

import com.google.android.material.bottomnavigation.BottomNavigationView;
import com.sedayar.app.databinding.ActivityMainBinding;
import com.sedayar.app.fragment.AudioFragment;
import com.sedayar.app.fragment.HandwritingFragment;
import com.sedayar.app.fragment.LiveVoiceFragment;
import com.sedayar.app.fragment.NotesFragment;
import com.sedayar.app.util.AppPrefs;
import com.sedayar.app.util.InsetsUtil;

/**
 * App shell: lapis toolbar with golden title + bottom navigation with four
 * sections (Notes / Live voice / Handwriting / Audio file) + a three-lines
 * menu button (تنظیمات / درباره) in the corner.
 */
public class MainActivity extends AppCompatActivity {

    private static final String STATE_TAB = "selected_tab";

    private ActivityMainBinding binding;
    private int currentTab = R.id.nav_notes;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        binding = ActivityMainBinding.inflate(getLayoutInflater());
        setContentView(binding.getRoot());

        // Android 15 edge-to-edge: keep content below the clock and above the
        // gesture bar (the drawing page used to slide under the status bar).
        InsetsUtil.apply(binding.getRoot(), binding.bottomNav);

        // First launch: greet the user on the Persian-patterned welcome screen
        if (!AppPrefs.isWelcomed(this)) {
            startActivity(new Intent(this, WelcomeActivity.class));
        }

        setSupportActionBar(binding.toolbar);

        binding.bottomNav.setOnItemSelectedListener(item -> {
            if (item.getItemId() != currentTab) {
                currentTab = item.getItemId();
                switchFragment();
            }
            return true;
        });

        if (savedInstanceState != null) {
            currentTab = savedInstanceState.getInt(STATE_TAB, R.id.nav_notes);
        }
        binding.bottomNav.setSelectedItemId(currentTab);
    }

    @Override
    protected void onSaveInstanceState(@NonNull Bundle outState) {
        super.onSaveInstanceState(outState);
        outState.putInt(STATE_TAB, currentTab);
    }

    private void switchFragment() {
        Fragment fragment;
        int title;
        int id = currentTab;
        if (id == R.id.nav_live) {
            fragment = new LiveVoiceFragment();
            title = R.string.tab_live;
        } else if (id == R.id.nav_draw) {
            fragment = new HandwritingFragment();
            title = R.string.tab_draw;
        } else if (id == R.id.nav_audio) {
            fragment = new AudioFragment();
            title = R.string.tab_audio;
        } else {
            fragment = new NotesFragment();
            title = R.string.tab_notes;
        }
        if (getSupportActionBar() != null) {
            getSupportActionBar().setTitle(title);
        }
        FragmentManager fm = getSupportFragmentManager();
        FragmentTransaction tx = fm.beginTransaction()
                .setTransition(FragmentTransaction.TRANSIT_FRAGMENT_FADE)
                .replace(R.id.container, fragment);
        tx.commit();
        invalidateOptionsMenu();
    }

    // ----------------------------------------------------------------- menu

    @Override
    public boolean onCreateOptionsMenu(Menu menu) {
        getMenuInflater().inflate(R.menu.menu_main, menu);
        MenuItem searchItem = menu.findItem(R.id.action_search);
        SearchView searchView = (SearchView) searchItem.getActionView();
        if (searchView != null) {
            searchView.setQueryHint(getString(R.string.search_hint));
            searchView.setOnQueryTextListener(new SearchView.OnQueryTextListener() {
                @Override
                public boolean onQueryTextSubmit(String query) {
                    return true;
                }

                @Override
                public boolean onQueryTextChange(String newText) {
                    forwardQuery(newText);
                    return true;
                }
            });
        }
        return true;
    }

    @Override
    public boolean onPrepareOptionsMenu(Menu menu) {
        MenuItem search = menu.findItem(R.id.action_search);
        if (search != null) {
            search.setVisible(currentTab == R.id.nav_notes);
        }
        return super.onPrepareOptionsMenu(menu);
    }

    private void forwardQuery(String text) {
        Fragment current = getSupportFragmentManager().findFragmentById(R.id.container);
        if (current instanceof NotesFragment) {
            ((NotesFragment) current).setSearchQuery(text == null ? "" : text);
        }
    }

    @Override
    public boolean onOptionsItemSelected(@NonNull MenuItem item) {
        int id = item.getItemId();
        if (id == R.id.action_settings) {
            startActivity(new Intent(this, SettingsActivity.class));
            return true;
        }
        if (id == R.id.action_about) {
            startActivity(new Intent(this, AboutActivity.class));
            return true;
        }
        if (id == R.id.action_premium) {
            startActivity(new Intent(this, PremiumActivity.class));
            return true;
        }
        return super.onOptionsItemSelected(item);
    }
}
