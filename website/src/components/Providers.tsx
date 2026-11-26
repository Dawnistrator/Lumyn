"use client";

import React, { createContext, useContext, useEffect, useState } from "react";
import { dictionaries, type Copy, type Locale } from "@/lib/i18n";

export type ThemeMode = "dark" | "light";

interface AppPreferencesContextValue {
  theme: ThemeMode;
  setTheme: (theme: ThemeMode) => void;
  toggleTheme: () => void;
  locale: Locale;
  setLocale: (locale: Locale) => void;
  copy: Copy;
}

const AppPreferencesContext = createContext<AppPreferencesContextValue | null>(null);

const THEME_STORAGE_KEY = "lumyn-web-theme";
const LOCALE_STORAGE_KEY = "lumyn-web-locale";

export function Providers({ children }: { children: React.ReactNode }) {
  const [theme, setThemeState] = useState<ThemeMode>("dark");
  const [locale, setLocaleState] = useState<Locale>("en");

  useEffect(() => {
    try {
      const savedTheme = window.localStorage.getItem(THEME_STORAGE_KEY);
      if (savedTheme === "light" || savedTheme === "dark") {
        setThemeState(savedTheme);
        document.documentElement.setAttribute("data-theme", savedTheme);
      } else {
        document.documentElement.setAttribute("data-theme", "dark");
      }

      const savedLocale = window.localStorage.getItem(LOCALE_STORAGE_KEY);
      if (savedLocale === "id" || savedLocale === "en") {
        setLocaleState(savedLocale);
        document.documentElement.lang = savedLocale;
      }
    } catch {
      document.documentElement.setAttribute("data-theme", "dark");
    }
  }, []);

  const setTheme = (nextTheme: ThemeMode) => {
    setThemeState(nextTheme);
    document.documentElement.setAttribute("data-theme", nextTheme);
    try {
      window.localStorage.setItem(THEME_STORAGE_KEY, nextTheme);
    } catch {
      // Ignore storage failures in private sessions
    }
  };

  const toggleTheme = () => {
    setTheme(theme === "dark" ? "light" : "dark");
  };

  const setLocale = (nextLocale: Locale) => {
    setLocaleState(nextLocale);
    document.documentElement.lang = nextLocale;
    try {
      window.localStorage.setItem(LOCALE_STORAGE_KEY, nextLocale);
    } catch {
      // Ignore storage failures in private sessions
    }
  };

  return (
    <AppPreferencesContext.Provider
      value={{
        theme,
        setTheme,
        toggleTheme,
        locale,
        setLocale,
        copy: dictionaries[locale],
      }}
    >
      {children}
    </AppPreferencesContext.Provider>
  );
}

export function useAppPreferences(): AppPreferencesContextValue {
  const ctx = useContext(AppPreferencesContext);
  if (!ctx) {
    throw new Error("useAppPreferences must be used within Providers");
  }
  return ctx;
}
