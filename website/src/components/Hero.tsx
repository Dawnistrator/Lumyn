"use client";

import React from "react";
import { ArrowDown, ArrowUpRight } from "@phosphor-icons/react";
import { useAppPreferences } from "@/components/Providers";
import { GITHUB_REPO_URL } from "@/lib/releases";

interface HeroProps {
  latestTag: string;
}

export function Hero({ latestTag }: HeroProps) {
  const { copy } = useAppPreferences();
  const primaryLabel =
    latestTag === "v1.0.0"
      ? copy.hero.primaryCta
      : copy.hero.primaryCta.replace("v1.0.0", latestTag);

  return (
    <section className="px-4 pt-8 pb-12 sm:px-6 md:pt-14 md:pb-20 lg:pt-16 lg:pb-24">
      <div className="mx-auto grid max-w-6xl items-center gap-8 md:grid-cols-12 md:gap-8 lg:gap-12">
        <div className="flex min-w-0 flex-col items-start md:col-span-7">
          <h1 className="heading-1 max-w-[680px] whitespace-pre-line text-[var(--text-primary)]">
            {copy.hero.headline}
          </h1>

          <p className="title-2 mt-5 max-w-[600px] text-[var(--text-secondary)] sm:mt-6">
            {copy.hero.subheadline}
          </p>

          <div className="mt-6 flex w-full flex-col gap-3 sm:mt-8 sm:w-auto sm:flex-row sm:flex-wrap sm:items-center sm:gap-4">
            <a
              href="#download"
              className="btn-primary button-md inline-flex min-h-12 w-full items-center justify-center gap-2 rounded-xl px-5 py-3 text-center no-underline sm:w-auto"
            >
              <span>{primaryLabel}</span>
              <ArrowDown size={18} aria-hidden="true" />
            </a>

            <a
              href={GITHUB_REPO_URL}
              target="_blank"
              rel="noopener noreferrer"
              className="btn-secondary button-md inline-flex min-h-12 w-full items-center justify-center gap-2 rounded-xl px-5 py-3 text-center no-underline sm:w-auto"
            >
              <span>{copy.hero.secondaryCta}</span>
              <ArrowUpRight size={18} aria-hidden="true" />
            </a>
          </div>

          <p className="caption-2 mono-tabular mt-6 text-[var(--text-muted)]">
            {copy.hero.proofLine}
          </p>
        </div>

        <div className="flex min-w-0 justify-center md:col-span-5 md:justify-end">
          <div
            className="w-full max-w-[240px] rounded-3xl p-2 sm:max-w-[264px] md:max-w-[272px] lg:max-w-[310px]"
            style={{
              backgroundColor: "var(--surface)",
              border: "1px solid var(--hairline-strong)",
              boxShadow: "var(--shadow-device)",
            }}
          >
            <img
              src="/screenshots/now-playing.png"
              alt={copy.hero.heroImageAlt}
              width={720}
              height={1507}
              fetchPriority="high"
              loading="eager"
              decoding="sync"
              className="block aspect-[720/1507] h-auto w-full rounded-2xl"
            />
          </div>
        </div>
      </div>
    </section>
  );
}
