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
    <section className="px-4 pt-12 pb-16 sm:px-6 md:pt-16 md:pb-24">
      <div className="mx-auto grid max-w-6xl items-center gap-10 lg:grid-cols-12 lg:gap-12">
        <div className="flex flex-col items-start lg:col-span-7">
          <h1 className="heading-1 max-w-[680px] whitespace-pre-line text-[var(--text-primary)]">
            {copy.hero.headline}
          </h1>

          <p className="title-2 mt-6 max-w-[600px] text-[var(--text-secondary)]">
            {copy.hero.subheadline}
          </p>

          <div className="mt-8 flex flex-wrap items-center gap-4">
            <a
              href="#download"
              className="btn-primary button-md inline-flex min-h-12 items-center gap-2 rounded-xl px-5 py-3 no-underline"
            >
              <span>{primaryLabel}</span>
              <ArrowDown size={18} aria-hidden="true" />
            </a>

            <a
              href={GITHUB_REPO_URL}
              target="_blank"
              rel="noopener noreferrer"
              className="btn-secondary button-md inline-flex min-h-12 items-center gap-2 rounded-xl px-5 py-3 no-underline"
            >
              <span>{copy.hero.secondaryCta}</span>
              <ArrowUpRight size={18} aria-hidden="true" />
            </a>
          </div>

          <p className="caption-2 mono-tabular mt-6 text-[var(--text-muted)]">
            {copy.hero.proofLine}
          </p>
        </div>

        <div className="flex justify-center lg:col-span-5 lg:justify-end">
          <div
            className="w-full max-w-[290px] rounded-3xl p-2 sm:max-w-[310px]"
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
              className="block h-auto w-full rounded-2xl"
            />
          </div>
        </div>
      </div>
    </section>
  );
}
