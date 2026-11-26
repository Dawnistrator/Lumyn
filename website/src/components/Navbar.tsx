"use client";

import React, { useEffect, useState } from "react";
import Link from "next/link";
import { List, Moon, Sun, X } from "@phosphor-icons/react";
import { LumynMark } from "@/components/LumynMark";
import { useAppPreferences } from "@/components/Providers";

interface NavbarProps {
  isSubPage?: boolean;
}

export function Navbar({ isSubPage = false }: NavbarProps) {
  const { theme, toggleTheme, locale, setLocale, copy } = useAppPreferences();
  const [menuOpen, setMenuOpen] = useState(false);

  const navItems = isSubPage
    ? [{ href: "/", label: copy.privacyPage.backHome }]
    : [
        { href: "#signal-path", label: copy.nav.signalPath },
        { href: "#capabilities", label: copy.nav.features },
        { href: "#privacy", label: copy.nav.privacy },
        { href: "#download", label: copy.nav.download },
        { href: "#faq", label: copy.nav.faq },
      ];

  useEffect(() => {
    if (!menuOpen) return;
    const onKeyDown = (event: KeyboardEvent) => {
      if (event.key === "Escape") {
        setMenuOpen(false);
      }
    };
    window.addEventListener("keydown", onKeyDown);
    return () => window.removeEventListener("keydown", onKeyDown);
  }, [menuOpen]);

  return (
    <header className="sticky top-0 z-40 px-4 pt-3 sm:px-6 sm:pt-4">
      <div className="mx-auto max-w-6xl">
        <nav
          aria-label="Primary"
          className="flex min-h-14 items-center justify-between gap-3 rounded-2xl px-3 py-2 backdrop-blur-md"
          style={{
            backgroundColor: "var(--surface-nav)",
            border: "1px solid var(--hairline)",
          }}
        >
          <Link
            href="/"
            className="flex min-h-11 items-center gap-2.5 rounded-xl px-2 text-inherit no-underline"
          >
            <LumynMark className="h-7 w-7 shrink-0" />
            <span className="title-3 font-semibold tracking-tight text-[var(--text-primary)]">
              Lumyn
            </span>
          </Link>

          <div className="hidden items-center gap-1 lg:flex">
            {navItems.map((item) => (
              <Link
                key={item.href}
                href={item.href}
                className="body-5 rounded-xl px-3 py-2 text-[var(--text-secondary)] no-underline transition-colors duration-200 hover:bg-[var(--surface-raised)] hover:text-[var(--text-primary)]"
              >
                {item.label}
              </Link>
            ))}
          </div>

          <div className="flex items-center gap-2">
            <div
              role="group"
              aria-label={copy.nav.languageLabel}
              className="flex items-center rounded-xl p-1"
              style={{
                backgroundColor: "var(--surface-raised)",
                border: "1px solid var(--hairline)",
              }}
            >
              {(["en", "id"] as const).map((lang) => {
                const active = locale === lang;
                return (
                  <button
                    key={lang}
                    type="button"
                    onClick={() => setLocale(lang)}
                    aria-pressed={active}
                    className="caption-4 min-h-9 min-w-9 rounded-lg px-2.5 uppercase transition-colors duration-200"
                    style={{
                      backgroundColor: active ? "var(--surface)" : "transparent",
                      color: active ? "var(--text-primary)" : "var(--text-muted)",
                    }}
                  >
                    {lang}
                  </button>
                );
              })}
            </div>

            <button
              type="button"
              onClick={toggleTheme}
              aria-label={theme === "dark" ? copy.nav.themeToLight : copy.nav.themeToDark}
              className="btn-secondary flex h-11 w-11 items-center justify-center rounded-xl"
            >
              {theme === "dark" ? (
                <Sun size={18} aria-hidden="true" />
              ) : (
                <Moon size={18} aria-hidden="true" />
              )}
            </button>

            {!isSubPage && (
              <a
                href="#download"
                className="btn-primary button-sm hidden min-h-11 items-center rounded-xl px-4 py-2 no-underline sm:inline-flex"
              >
                {copy.nav.downloadCta}
              </a>
            )}

            <button
              type="button"
              onClick={() => setMenuOpen((prev) => !prev)}
              aria-expanded={menuOpen}
              aria-controls="mobile-nav-panel"
              className="btn-secondary button-sm flex min-h-11 items-center gap-1.5 rounded-xl px-3 lg:hidden"
            >
              {menuOpen ? (
                <X size={18} aria-hidden="true" />
              ) : (
                <List size={18} aria-hidden="true" />
              )}
              <span>{menuOpen ? copy.nav.menuClose : copy.nav.menuOpen}</span>
            </button>
          </div>
        </nav>

        {menuOpen && (
          <div
            id="mobile-nav-panel"
            className="mt-2 rounded-2xl p-4 backdrop-blur-lg lg:hidden"
            style={{
              backgroundColor: "var(--surface-menu)",
              border: "1px solid var(--hairline-strong)",
            }}
          >
            <div className="flex flex-col gap-2">
              {navItems.map((item) => (
                <Link
                  key={item.href}
                  href={item.href}
                  onClick={() => setMenuOpen(false)}
                  className="title-3 flex min-h-11 items-center rounded-xl px-3 py-2 text-[var(--text-primary)] no-underline transition-colors duration-200 hover:bg-[var(--surface-raised)]"
                >
                  {item.label}
                </Link>
              ))}
              {!isSubPage && (
                <a
                  href="#download"
                  onClick={() => setMenuOpen(false)}
                  className="btn-primary button-md mt-2 flex min-h-12 items-center justify-center rounded-xl px-4 py-3 no-underline"
                >
                  {copy.hero.primaryCta}
                </a>
              )}
            </div>
          </div>
        )}
      </div>
    </header>
  );
}
