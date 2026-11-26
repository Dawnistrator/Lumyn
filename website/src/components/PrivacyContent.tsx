"use client";

import React from "react";
import Link from "next/link";
import { ArrowLeft } from "@phosphor-icons/react";
import { Navbar } from "@/components/Navbar";
import { Footer } from "@/components/Footer";
import { useAppPreferences } from "@/components/Providers";

export function PrivacyContent() {
  const { copy } = useAppPreferences();

  return (
    <div className="flex min-h-screen flex-col">
      <Navbar isSubPage />
      <main id="main-content" className="flex-1 px-4 py-12 sm:px-6 md:py-16">
        <div className="mx-auto max-w-3xl">
          <Link
            href="/"
            className="button-sm inline-flex min-h-11 items-center gap-2 text-[var(--accent)] no-underline hover:underline"
          >
            <ArrowLeft size={16} aria-hidden="true" />
            <span>{copy.privacyPage.backHome}</span>
          </Link>

          <h1 className="heading-2 mt-6 text-[var(--text-primary)]">
            {copy.privacyPage.title}
          </h1>
          <p className="title-2 mt-3 text-[var(--text-secondary)]">
            {copy.privacyPage.subtitle}
          </p>
          <p className="caption-2 mono-tabular mt-2 text-[var(--text-muted)]">
            {copy.privacyPage.updated}
          </p>

          <div className="mt-10 space-y-6">
            {copy.privacyPage.sections.map((section) => (
              <article key={section.heading} className="lumyn-card rounded-2xl p-6">
                <h2 className="title-2 text-[var(--text-primary)]">
                  {section.heading}
                </h2>
                <p className="body-3 mt-3 text-[var(--text-secondary)]">
                  {section.body}
                </p>
              </article>
            ))}
          </div>
        </div>
      </main>
      <Footer hideFinalCta />
    </div>
  );
}
