"use client";

import React, { useState } from "react";
import Link from "next/link";
import { ArrowRight, Check, Copy, ShieldCheck } from "@phosphor-icons/react";
import { useAppPreferences } from "@/components/Providers";

export function PrivacyProof() {
  const { copy } = useAppPreferences();
  const [copied, setCopied] = useState(false);

  const handleCopy = async () => {
    try {
      await navigator.clipboard.writeText(copy.privacy.verifyCommand);
      setCopied(true);
      window.setTimeout(() => setCopied(false), 2200);
    } catch {
      setCopied(false);
    }
  };

  return (
    <section
      id="privacy"
      className="px-4 py-12 sm:px-6 md:py-20 lg:py-24"
      style={{ borderTop: "1px solid var(--hairline)" }}
    >
      <div className="mx-auto grid max-w-6xl gap-8 md:grid-cols-12 md:items-start lg:gap-10">
        <div className="min-w-0 md:col-span-7">
          <p className="body-5 text-[var(--accent)]">{copy.privacy.sectionLabel}</p>
          <h2 className="heading-3 mt-2 max-w-[680px] text-[var(--text-primary)]">
            {copy.privacy.heading}
          </h2>
          <p className="body-3 mt-4 max-w-[640px] text-[var(--text-secondary)]">
            {copy.privacy.body}
          </p>

          <div className="mt-6">
            <p className="body-5 text-[var(--text-primary)]">{copy.privacy.verifyLabel}</p>
            <div className="lumyn-raised mt-3 flex flex-col gap-3 rounded-2xl p-3 lg:flex-row lg:items-center lg:justify-between">
              <code className="caption-2 mono-tabular min-w-0 flex-1 break-words [overflow-wrap:anywhere] px-2 py-1 text-[var(--text-primary)]">
                {copy.privacy.verifyCommand}
              </code>
              <button
                type="button"
                onClick={handleCopy}
                aria-live="polite"
                className="btn-secondary button-sm inline-flex min-h-11 w-full shrink-0 items-center justify-center gap-2 rounded-xl px-4 py-2 lg:w-auto"
              >
                {copied ? (
                  <Check size={16} aria-hidden="true" className="text-[var(--accent)]" />
                ) : (
                  <Copy size={16} aria-hidden="true" />
                )}
                <span>{copied ? copy.privacy.copiedCmd : copy.privacy.copyCmd}</span>
              </button>
            </div>
          </div>

          <div className="mt-6">
            <Link
              href="/privacy"
              className="button-sm inline-flex min-h-11 items-center gap-2 text-[var(--accent)] no-underline hover:underline"
            >
              <span>{copy.privacy.policyLink}</span>
              <ArrowRight size={16} aria-hidden="true" />
            </Link>
          </div>
        </div>

        <aside className="lumyn-card min-w-0 rounded-2xl p-4 sm:p-6 md:col-span-5">
          <div className="flex min-w-0 items-center gap-2.5">
            <ShieldCheck size={20} aria-hidden="true" className="shrink-0 text-[var(--accent)]" />
            <h3 className="title-3 min-w-0 break-words text-[var(--text-primary)]">
              {copy.privacy.checklistTitle}
            </h3>
          </div>

          <ul className="mt-5 space-y-3">
            {copy.privacy.checklist.map((item) => (
              <li
                key={item}
                className="body-5 flex items-start gap-2.5 rounded-xl p-3 text-[var(--text-secondary)] lumyn-raised"
              >
                <Check
                  size={16}
                  aria-hidden="true"
                  className="mt-0.5 shrink-0 text-[var(--accent)]"
                />
                <span className="mono-tabular min-w-0 flex-1 break-words [overflow-wrap:anywhere] text-xs leading-relaxed sm:text-sm">
                  {item}
                </span>
              </li>
            ))}
          </ul>
        </aside>
      </div>
    </section>
  );
}
