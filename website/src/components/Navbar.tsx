"use client";

import React, { useCallback, useEffect, useRef, useState } from "react";
import Link from "next/link";
import { List, Moon, Sun, X } from "@phosphor-icons/react";
import { LunariaMark } from "@/components/LunariaMark";
import { useAppPreferences } from "@/components/Providers";

interface NavbarProps {
  isSubPage?: boolean;
  latestTag?: string;
}

function supportsNativePopover(): boolean {
  return typeof HTMLElement !== "undefined" && Object.hasOwn(HTMLElement.prototype, "popover");
}

export function Navbar({ isSubPage = false, latestTag = "v1.0.0" }: NavbarProps) {
  const { theme, toggleTheme, locale, setLocale, copy } = useAppPreferences();
  const [menuOpen, setMenuOpen] = useState(false);
  const panelRef = useRef<HTMLDivElement | null>(null);
  const triggerRef = useRef<HTMLButtonElement | null>(null);

  const mobileCtaLabel =
    latestTag === "v1.0.0"
      ? copy.hero.primaryCta
      : copy.hero.primaryCta.replace("v1.0.0", latestTag);

  const navItems = isSubPage
    ? [{ href: "/", label: copy.privacyPage.backHome }]
    : [
        { href: "#signal-path", label: copy.nav.signalPath },
        { href: "#capabilities", label: copy.nav.features },
        { href: "#privacy", label: copy.nav.privacy },
        { href: "#download", label: copy.nav.download },
        { href: "#faq", label: copy.nav.faq },
      ];

  const closeMenu = useCallback(() => {
    const panel = panelRef.current;
    if (panel && supportsNativePopover() && panel.matches(":popover-open")) {
      panel.hidePopover();
    }
    setMenuOpen(false);
  }, []);

  useEffect(() => {
    const panel = panelRef.current;
    if (!panel) return;

    const handleToggle = (event: Event) => {
      const toggleEvent = event as ToggleEvent;
      setMenuOpen(toggleEvent.newState === "open");
    };

    panel.addEventListener("toggle", handleToggle);

    const desktopQuery = window.matchMedia("(min-width: 1024px)");
    const handleViewportChange = (event: MediaQueryListEvent) => {
      if (event.matches) {
        closeMenu();
      }
    };
    desktopQuery.addEventListener("change", handleViewportChange);

    return () => {
      panel.removeEventListener("toggle", handleToggle);
      desktopQuery.removeEventListener("change", handleViewportChange);
    };
  }, [closeMenu]);

  useEffect(() => {
    if (!menuOpen) return;

    const handleKeyDown = (event: KeyboardEvent) => {
      if (event.key === "Escape") {
        closeMenu();
        triggerRef.current?.focus();
      }
    };

    const handleFocusOut = (event: FocusEvent) => {
      const nextTarget = event.relatedTarget as Node | null;
      if (!nextTarget) return;
      if (panelRef.current?.contains(nextTarget) || triggerRef.current?.contains(nextTarget)) {
        return;
      }
      closeMenu();
    };

    window.addEventListener("keydown", handleKeyDown);
    const panel = panelRef.current;
    panel?.addEventListener("focusout", handleFocusOut);

    if (!supportsNativePopover()) {
      const handlePointerDown = (event: PointerEvent) => {
        const target = event.target as Node | null;
        if (!target) return;
        if (panelRef.current?.contains(target) || triggerRef.current?.contains(target)) {
          return;
        }
        setMenuOpen(false);
      };
      document.addEventListener("pointerdown", handlePointerDown);
      return () => {
        window.removeEventListener("keydown", handleKeyDown);
        panel?.removeEventListener("focusout", handleFocusOut);
        document.removeEventListener("pointerdown", handlePointerDown);
      };
    }

    return () => {
      window.removeEventListener("keydown", handleKeyDown);
      panel?.removeEventListener("focusout", handleFocusOut);
    };
  }, [menuOpen, closeMenu]);

  const handleMenuButtonClick = () => {
    if (supportsNativePopover()) {
      return;
    }
    setMenuOpen((prev) => !prev);
  };

  return (
    <header className="site-header sticky top-0 z-40">
      <div className="mx-auto max-w-6xl">
        <nav
          aria-label={copy.nav.primaryNavLabel}
          className="flex min-h-13 flex-wrap items-center justify-between gap-2 rounded-2xl px-2.5 py-1.5 backdrop-blur-md sm:min-h-14 sm:gap-3 sm:px-3 sm:py-2"
          style={{
            backgroundColor: "var(--surface-nav)",
            border: "1px solid var(--hairline)",
          }}
        >
          <Link
            href="/"
            aria-current={!isSubPage ? "page" : undefined}
            className="flex min-h-11 shrink-0 items-center gap-2 rounded-xl px-1.5 text-inherit no-underline sm:gap-2.5 sm:px-2"
          >
            <LunariaMark className="h-7 w-7 shrink-0" />
            <span className="title-3 whitespace-nowrap font-semibold text-[var(--text-primary)]">
              Lunaria
            </span>
          </Link>

          <ul className="hidden flex-wrap items-center gap-0.5 lg:flex xl:gap-1">
            {navItems.map((item) => (
              <li key={item.href}>
                <Link
                  href={item.href}
                  className="body-5 inline-flex min-h-11 items-center whitespace-nowrap rounded-xl px-2.5 py-2 text-[var(--text-secondary)] no-underline transition-colors duration-200 hover:bg-[var(--surface-raised)] hover:text-[var(--text-primary)] xl:px-3"
                >
                  {item.label}
                </Link>
              </li>
            ))}
          </ul>

          <div className="flex min-w-0 flex-wrap items-center gap-1.5 sm:gap-2">
            <div
              role="group"
              aria-label={copy.nav.languageLabel}
              className="hidden items-center gap-1 rounded-xl p-1 sm:flex"
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
                    lang={lang}
                    aria-label={copy.nav.langNames[lang]}
                    onClick={() => setLocale(lang)}
                    aria-pressed={active}
                    className="caption-4 inline-flex min-h-11 min-w-11 items-center justify-center rounded-lg px-2.5 uppercase transition-colors duration-200"
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
              className="btn-secondary flex h-11 w-11 shrink-0 items-center justify-center rounded-xl"
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
                className="btn-primary button-sm hidden min-h-11 shrink-0 items-center whitespace-nowrap rounded-xl px-4 py-2 no-underline md:inline-flex"
              >
                {copy.nav.downloadCta}
              </a>
            )}

            <button
              ref={triggerRef}
              type="button"
              onClick={handleMenuButtonClick}
              popoverTarget="mobile-nav-panel"
              popoverTargetAction="toggle"
              aria-expanded={menuOpen}
              aria-controls="mobile-nav-panel"
              className="nav-menu-trigger btn-secondary button-sm flex min-h-11 shrink-0 items-center gap-1.5 rounded-xl px-3 lg:hidden"
            >
              {menuOpen ? (
                <X size={18} aria-hidden="true" />
              ) : (
                <List size={18} aria-hidden="true" />
              )}
              <span>{menuOpen ? copy.nav.menuClose : copy.nav.menuOpen}</span>
            </button>

            <div
              ref={panelRef}
              id="mobile-nav-panel"
              popover="auto"
              data-fallback-open={menuOpen ? "true" : "false"}
              className="nav-menu-panel rounded-2xl p-3.5 backdrop-blur-lg sm:p-4 lg:hidden"
              style={{
                backgroundColor: "var(--surface-menu)",
                border: "1px solid var(--hairline-strong)",
              }}
            >
              <div className="flex flex-col gap-1.5">
                <ul className="flex flex-col gap-1.5">
                  {navItems.map((item) => (
                    <li key={item.href}>
                      <Link
                        href={item.href}
                        onClick={closeMenu}
                        className="title-3 flex min-h-11 items-center rounded-xl px-3 py-2 text-[var(--text-primary)] no-underline transition-colors duration-200 hover:bg-[var(--surface-raised)]"
                      >
                        {item.label}
                      </Link>
                    </li>
                  ))}
                </ul>

                <div
                  className="mt-2 flex items-center justify-between gap-3 pt-3 sm:hidden"
                  style={{ borderTop: "1px solid var(--hairline)" }}
                >
                  <span className="body-5 text-[var(--text-secondary)]">
                    {copy.nav.languageLabel}
                  </span>
                  <div
                    role="group"
                    aria-label={copy.nav.languageLabel}
                    className="flex items-center gap-1.5 rounded-xl p-1"
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
                          lang={lang}
                          aria-label={copy.nav.langNames[lang]}
                          onClick={() => setLocale(lang)}
                          aria-pressed={active}
                          className="caption-4 inline-flex min-h-11 min-w-11 items-center justify-center rounded-lg px-2.5 uppercase transition-colors duration-200"
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
                </div>

                {!isSubPage && (
                  <a
                    href="#download"
                    onClick={closeMenu}
                    className="btn-primary button-md mt-2 flex min-h-12 w-full items-center justify-center rounded-xl px-4 py-3 text-center no-underline md:hidden"
                  >
                    {mobileCtaLabel}
                  </a>
                )}
              </div>
            </div>
          </div>
        </nav>
      </div>
    </header>
  );
}
