"use client";

import React, { useEffect, useRef, useState } from "react";
import Link from "next/link";
import { ArrowRight, Check, Copy, ShieldCheck } from "@phosphor-icons/react";
import { useAppPreferences } from "@/components/Providers";

interface PrivacyProofProps {
  latestTag?: string;
}

export function PrivacyProof({ latestTag = "v1.0.0" }: PrivacyProofProps) {
  const { copy } = useAppPreferences();
  const [copyStatus, setCopyStatus] = useState<"idle" | "copied" | "failed">("idle");
  const copyTimerRef = useRef<number | null>(null);

  useEffect(() => {
    return () => {
      if (copyTimerRef.current !== null) {
        window.clearTimeout(copyTimerRef.current);
      }
    };
  }, []);

  const verifyCommand =
    latestTag === "v1.0.0"
      ? copy.privacy.verifyCommand
      : copy.privacy.verifyCommand.replace("v1.0.0", latestTag);

  const handleCopy = async () => {
    if (copyTimerRef.current !== null) {
      window.clearTimeout(copyTimerRef.current);
      copyTimerRef.current = null;
    }
    try {
      await navigator.clipboard.writeText(verifyCommand);
      setCopyStatus("copied");
      copyTimerRef.current = window.setTimeout(() => setCopyStatus("idle"), 2200);
    } catch {
      setCopyStatus("failed");
      copyTimerRef.current = window.setTimeout(() => setCopyStatus("idle"), 3000);
    }
  };

  const liveAnnouncement =
    copyStatus === "copied"
      ? copy.privacy.copiedCmd
      : copyStatus === "failed"
        ? copy.privacy.copyFailed
        : "";

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
            <div className="lunaria-raised mt-3 flex flex-col gap-3 rounded-2xl p-3 lg:flex-row lg:items-center lg:justify-between">
              <code className="caption-2 mono-tabular min-w-0 flex-1 break-words [overflow-wrap:anywhere] px-2 py-1 text-[var(--text-primary)]">
                {verifyCommand}
              </code>
              <button
                type="button"
                onClick={handleCopy}
                className="btn-secondary button-sm inline-flex min-h-11 w-full shrink-0 items-center justify-center gap-2 rounded-xl px-4 py-2 lg:w-auto"
              >
                {copyStatus === "copied" ? (
                  <Check size={16} aria-hidden="true" className="text-[var(--accent)]" />
                ) : (
                  <Copy size={16} aria-hidden="true" />
                )}
                <span>
                  {copyStatus === "copied"
                    ? copy.privacy.copiedCmd
                    : copyStatus === "failed"
                      ? copy.privacy.copyFailed
                      : copy.privacy.copyCmd}
                </span>
              </button>
              <span role="status" aria-live="polite" className="sr-only">
                {liveAnnouncement}
              </span>
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

        <aside className="lunaria-card min-w-0 rounded-2xl p-4 sm:p-6 md:col-span-5">
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
                className="body-5 flex items-start gap-2.5 rounded-xl p-3 text-[var(--text-secondary)] lunaria-raised"
              >
                <Check
                  size={16}
                  aria-hidden="true"
                  className="mt-0.5 shrink-0 text-[var(--accent)]"
                />
                <span className="min-w-0 flex-1 break-words [overflow-wrap:anywhere] text-xs leading-relaxed sm:text-sm">
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
