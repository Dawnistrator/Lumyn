"use client";

import React from "react";
import Link from "next/link";
import { ArrowDown } from "@phosphor-icons/react";
import { LumynMark } from "@/components/LumynMark";
import { useAppPreferences } from "@/components/Providers";
import {
  GITHUB_ISSUES_URL,
  GITHUB_RELEASES_URL,
  GITHUB_REPO_URL,
} from "@/lib/releases";

interface FooterProps {
  latestTag?: string;
  hideFinalCta?: boolean;
}

export function Footer({ latestTag = "v1.0.0", hideFinalCta = false }: FooterProps) {
  const { copy } = useAppPreferences();
  const ctaLabel =
    latestTag === "v1.0.0"
      ? copy.footer.ctaButton
      : copy.footer.ctaButton.replace("v1.0.0", latestTag);

  return (
    <footer
      className="px-4 pt-12 pb-[max(3rem,env(safe-area-inset-bottom,0px))] sm:px-6 md:pt-16 md:pb-[max(4rem,env(safe-area-inset-bottom,0px))]"
      style={{ borderTop: "1px solid var(--hairline)" }}
    >
      <div className="mx-auto max-w-6xl">
        {!hideFinalCta && (
          <div className="lumyn-card mb-10 flex flex-col items-start justify-between gap-5 rounded-2xl p-5 sm:mb-12 sm:p-8 md:flex-row md:items-center">
            <h2 className="heading-4 max-w-[520px] text-[var(--text-primary)]">
              {copy.footer.ctaHeading}
            </h2>
            <a
              href="#download"
              className="btn-primary button-md inline-flex min-h-12 w-full shrink-0 items-center justify-center gap-2 rounded-xl px-5 py-3 text-center no-underline md:w-auto"
            >
              <span>{ctaLabel}</span>
              <ArrowDown size={18} aria-hidden="true" />
            </a>
          </div>
        )}

        <div className="flex flex-col justify-between gap-6 md:flex-row md:items-center">
          <div className="flex items-start gap-3">
            <LumynMark className="mt-0.5 h-6 w-6 shrink-0" />
            <div className="min-w-0">
              <div className="title-4 text-[var(--text-primary)]">Lumyn</div>
              <p className="caption-2 mt-0.5 text-[var(--text-muted)]">
                {copy.footer.note}
              </p>
            </div>
          </div>

          <div className="flex flex-wrap items-center gap-x-3 gap-y-1 sm:gap-x-4">
            <a
              href={GITHUB_REPO_URL}
              target="_blank"
              rel="noopener noreferrer"
              className="body-5 inline-flex min-h-11 items-center rounded-lg px-2 py-2 text-[var(--text-secondary)] no-underline transition-colors duration-200 hover:text-[var(--accent)]"
            >
              {copy.footer.links.github}
            </a>
            <a
              href={GITHUB_RELEASES_URL}
              target="_blank"
              rel="noopener noreferrer"
              className="body-5 inline-flex min-h-11 items-center rounded-lg px-2 py-2 text-[var(--text-secondary)] no-underline transition-colors duration-200 hover:text-[var(--accent)]"
            >
              {copy.footer.links.releases}
            </a>
            <Link
              href="/privacy"
              className="body-5 inline-flex min-h-11 items-center rounded-lg px-2 py-2 text-[var(--text-secondary)] no-underline transition-colors duration-200 hover:text-[var(--accent)]"
            >
              {copy.footer.links.privacy}
            </Link>
            <a
              href={GITHUB_ISSUES_URL}
              target="_blank"
              rel="noopener noreferrer"
              className="body-5 inline-flex min-h-11 items-center rounded-lg px-2 py-2 text-[var(--text-secondary)] no-underline transition-colors duration-200 hover:text-[var(--accent)]"
            >
              {copy.footer.links.issues}
            </a>
          </div>
        </div>
      </div>
    </footer>
  );
}
