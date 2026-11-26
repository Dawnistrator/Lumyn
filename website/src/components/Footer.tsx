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
      className="px-4 py-16 sm:px-6"
      style={{ borderTop: "1px solid var(--hairline)" }}
    >
      <div className="mx-auto max-w-6xl">
        {!hideFinalCta && (
          <div className="lumyn-card mb-12 flex flex-col items-start justify-between gap-6 rounded-2xl p-6 sm:flex-row sm:items-center sm:p-8">
            <h2 className="heading-4 max-w-[520px] text-[var(--text-primary)]">
              {copy.footer.ctaHeading}
            </h2>
            <a
              href="#download"
              className="btn-primary button-md inline-flex min-h-12 shrink-0 items-center gap-2 rounded-xl px-5 py-3 no-underline"
            >
              <span>{ctaLabel}</span>
              <ArrowDown size={18} aria-hidden="true" />
            </a>
          </div>
        )}

        <div className="flex flex-col justify-between gap-6 sm:flex-row sm:items-center">
          <div className="flex items-start gap-3">
            <LumynMark className="mt-0.5 h-6 w-6 shrink-0" />
            <div>
              <div className="title-4 text-[var(--text-primary)]">Lumyn</div>
              <p className="caption-2 mt-0.5 text-[var(--text-muted)]">
                {copy.footer.note}
              </p>
            </div>
          </div>

          <div className="flex flex-wrap items-center gap-5">
            <a
              href={GITHUB_REPO_URL}
              target="_blank"
              rel="noopener noreferrer"
              className="body-5 py-2 text-[var(--text-secondary)] no-underline hover:text-[var(--accent)]"
            >
              {copy.footer.links.github}
            </a>
            <a
              href={GITHUB_RELEASES_URL}
              target="_blank"
              rel="noopener noreferrer"
              className="body-5 py-2 text-[var(--text-secondary)] no-underline hover:text-[var(--accent)]"
            >
              {copy.footer.links.releases}
            </a>
            <Link
              href="/privacy"
              className="body-5 py-2 text-[var(--text-secondary)] no-underline hover:text-[var(--accent)]"
            >
              {copy.footer.links.privacy}
            </Link>
            <a
              href={GITHUB_ISSUES_URL}
              target="_blank"
              rel="noopener noreferrer"
              className="body-5 py-2 text-[var(--text-secondary)] no-underline hover:text-[var(--accent)]"
            >
              {copy.footer.links.issues}
            </a>
          </div>
        </div>
      </div>
    </footer>
  );
}
