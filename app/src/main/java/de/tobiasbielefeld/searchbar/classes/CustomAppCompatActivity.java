/*
 * Copyright (C) 2024  Tobias Bielefeld
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program.  If not, see <http://www.gnu.org/licenses/>.
 *
 * If you want to contact me, send me an e-mail at tobias.bielefeld@gmail.com
 */

package de.tobiasbielefeld.searchbar.classes;

import androidx.annotation.Nullable;
import androidx.appcompat.app.AppCompatActivity;
import androidx.appcompat.app.AppCompatDelegate;
import androidx.appcompat.widget.Toolbar;
import androidx.core.view.WindowCompat;
import androidx.core.view.WindowInsetsControllerCompat;

import android.content.pm.ActivityInfo;
import android.os.Bundle;
import android.view.MenuItem;
import android.view.View;
import android.view.Window;
import android.view.WindowManager;

import de.tobiasbielefeld.searchbar.R;

import static de.tobiasbielefeld.searchbar.SharedData.*;
import static de.tobiasbielefeld.searchbar.helper.InsetHelper.applyEdgelessMode;

import java.util.Locale;

public abstract class CustomAppCompatActivity extends AppCompatActivity {

    private Locale lastKnownLocale;
    private int lastKnownTheme;
    private int lastKnownOrientation;

    protected Toolbar toolbar; // needs to be set in class instantiation
    protected View systemTopSpacer; // needs to be set in class instantiation

    @Override
    protected void onCreate(@Nullable Bundle savedInstanceState) {
        reinitializeData(this);

        lastKnownLocale = Locale.getDefault();

        lastKnownTheme = getSavedTheme();
        applyTheme(lastKnownTheme);

        lastKnownOrientation = getSavedOrientation();
        setOrientation(lastKnownOrientation);

        applyEdgelessMode(this);
        super.onCreate(savedInstanceState);
    }

    protected void onResume() {
        super.onResume();
        showOrHideStatusBar(getWindow(), getSavedStatusBar());
        changeToolbarColor();

        Locale newLocale = Locale.getDefault();
        if (!lastKnownLocale.getLanguage().equals(newLocale.getLanguage())) {
            lastKnownLocale = newLocale;
            recreate();
        }

        int newTheme = getSavedTheme();
        if (lastKnownTheme != newTheme) {
            lastKnownTheme = newTheme;
            applyTheme(newTheme);
            recreate();
        }

        int newOrientation = getSavedOrientation();
        if (lastKnownOrientation != newOrientation) {
            lastKnownOrientation = newOrientation;
            setOrientation(lastKnownOrientation);
        }
    }

    @Override
    public boolean onOptionsItemSelected(MenuItem item) {
        // Respond to the action bar's Up/Home button
        if (item.getItemId() == android.R.id.home) {
            getOnBackPressedDispatcher().onBackPressed();
            return true;
        }
        return super.onOptionsItemSelected(item);
    }

    public void applyTheme(int theme) {
        int night = theme == THEME_LIGHT
                ? AppCompatDelegate.MODE_NIGHT_NO
                : AppCompatDelegate.MODE_NIGHT_YES;
        getDelegate().setLocalNightMode(night);
        setTheme(styleFor(theme));
    }

    public static int styleFor(int theme) {
        if (theme == THEME_LIGHT) {
            return R.style.ThemeLight;
        }
        if (theme == THEME_TERMINAL) {
            return R.style.ThemeTerminal;
        }
        return R.style.ThemeDark;
    }

    public void setOrientation(int orientation) {
        switch (orientation) {
            case 1 -> setRequestedOrientation(ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED);
            case 2 -> setRequestedOrientation(ActivityInfo.SCREEN_ORIENTATION_PORTRAIT);
            case 3 -> setRequestedOrientation(ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE);
            case 4 -> setRequestedOrientation(ActivityInfo.SCREEN_ORIENTATION_REVERSE_LANDSCAPE);
        }
    }

    public void changeToolbarColor() {
        int theme = getSavedTheme();
        int savedColor = getSavedToolbarColor();
        if (theme == THEME_TERMINAL) {
            savedColor = 0xFF000000;
        } else if (savedColor == 0xFF3F51B5 || savedColor == 0xFF32408B) {
            savedColor = theme == THEME_LIGHT ? 0xFFF7F7F7 : 0xFF000000;
            putSavedToolbarColor(savedColor);
        } else if (theme == THEME_LIGHT && savedColor == 0xFF000000) {
            savedColor = 0xFFF7F7F7;
        } else if (theme == THEME_DARK && savedColor == 0xFFF7F7F7) {
            savedColor = 0xFF000000;
        }

        Window window = getWindow();
        window.clearFlags(WindowManager.LayoutParams.FLAG_TRANSLUCENT_STATUS);
        window.addFlags(WindowManager.LayoutParams.FLAG_DRAWS_SYSTEM_BAR_BACKGROUNDS);
        window.setStatusBarColor(savedColor);
        window.setNavigationBarColor(theme == THEME_LIGHT ? 0xFFF7F7F7 : 0xFF000000);
        toolbar.setBackgroundColor(savedColor);
        systemTopSpacer.setBackgroundColor(savedColor);
        if (theme == THEME_TERMINAL) {
            toolbar.setTitleTextColor(0xFF33FF33);
        } else if (theme == THEME_LIGHT) {
            toolbar.setTitleTextColor(0xFF1A1A1A);
        } else {
            toolbar.setTitleTextColor(0xFFE8E8E8);
        }
        setSystemBarIcons(window, isLightColor(savedColor), theme == THEME_LIGHT);
    }

    private static void setSystemBarIcons(Window window, boolean lightStatus, boolean lightNavigation) {
        WindowInsetsControllerCompat controller = WindowCompat.getInsetsController(window, window.getDecorView());
        controller.setAppearanceLightStatusBars(lightStatus);
        controller.setAppearanceLightNavigationBars(lightNavigation);
    }

    private static boolean isLightColor(int color) {
        int red = (color >> 16) & 0xFF;
        int green = (color >> 8) & 0xFF;
        int blue = color & 0xFF;
        return (red * 299 + green * 587 + blue * 114) / 1000 > 160;
    }
}
