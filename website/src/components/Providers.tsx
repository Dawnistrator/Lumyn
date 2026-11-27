"use client";

import React, {
  createContext,
  use,
  useCallback,
  useEffect,
  useMemo,
  useState,
} from "react";
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

const THEME_STORAGE_KEY = "lunaria-web-theme";
const LOCALE_STORAGE_KEY = "lunaria-web-locale";

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

    const handleStorage = (event: StorageEvent) => {
      if (event.key === THEME_STORAGE_KEY) {
        const nextTheme: ThemeMode = event.newValue === "light" ? "light" : "dark";
        setThemeState(nextTheme);
        document.documentElement.setAttribute("data-theme", nextTheme);
      } else if (event.key === LOCALE_STORAGE_KEY) {
        const nextLocale: Locale = event.newValue === "id" ? "id" : "en";
        setLocaleState(nextLocale);
        document.documentElement.lang = nextLocale;
      }
    };

    window.addEventListener("storage", handleStorage);
    return () => window.removeEventListener("storage", handleStorage);
  }, []);

  const setTheme = useCallback((nextTheme: ThemeMode) => {
    setThemeState(nextTheme);
    document.documentElement.setAttribute("data-theme", nextTheme);
    try {
      window.localStorage.setItem(THEME_STORAGE_KEY, nextTheme);
    } catch {
      // Ignore storage failures in private sessions
    }
  }, []);

  const toggleTheme = useCallback(() => {
    setThemeState((prev) => {
      const next: ThemeMode = prev === "dark" ? "light" : "dark";
      document.documentElement.setAttribute("data-theme", next);
      try {
        window.localStorage.setItem(THEME_STORAGE_KEY, next);
      } catch {
        // Ignore storage failures in private sessions
      }
      return next;
    });
  }, []);

  const setLocale = useCallback((nextLocale: Locale) => {
    setLocaleState(nextLocale);
    document.documentElement.lang = nextLocale;
    try {
      window.localStorage.setItem(LOCALE_STORAGE_KEY, nextLocale);
    } catch {
      // Ignore storage failures in private sessions
    }
  }, []);

  const value = useMemo<AppPreferencesContextValue>(
    () => ({
      theme,
      setTheme,
      toggleTheme,
      locale,
      setLocale,
      copy: dictionaries[locale],
    }),
    [theme, setTheme, toggleTheme, locale, setLocale],
  );

  return <AppPreferencesContext value={value}>{children}</AppPreferencesContext>;
}

export function useAppPreferences(): AppPreferencesContextValue {
  const ctx = use(AppPreferencesContext);
  if (!ctx) {
    throw new Error("useAppPreferences must be used within Providers");
  }
  return ctx;
}
