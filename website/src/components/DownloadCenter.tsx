"use client";

import React, { useState } from "react";
import {
  ArrowsClockwise,
  ArrowUpRight,
  Check,
  Copy,
  DownloadSimple,
  WarningCircle,
} from "@phosphor-icons/react";
import { useAppPreferences } from "@/components/Providers";
import {
  GITHUB_RELEASES_URL,
  type ApkAsset,
  type ReleaseInfo,
  type ReleaseResult,
} from "@/lib/releases";

interface DownloadCenterProps {
  initialRelease: ReleaseInfo;
  initialSource: "github" | "fallback";
}

export function DownloadCenter({
  initialRelease,
  initialSource,
}: DownloadCenterProps) {
  const { locale, copy } = useAppPreferences();
  const [release, setRelease] = useState<ReleaseInfo>(initialRelease);
  const [source, setSource] = useState<"github" | "fallback">(initialSource);
  const [selectedAbi, setSelectedAbi] = useState<string>(
    initialRelease.assets[0]?.abi ?? "arm64-v8a",
  );
  const [isLoading, setIsLoading] = useState(false);
  const [errorMessage, setErrorMessage] = useState<string | null>(null);
  const [copiedSha, setCopiedSha] = useState(false);

  const selectedAsset: ApkAsset | undefined =
    release.assets.find((item) => item.abi === selectedAbi) ?? release.assets[0];

  const handleRefreshRelease = async () => {
    setIsLoading(true);
    setErrorMessage(null);
    try {
      const response = await fetch("/api/release", { cache: "no-store" });
      if (!response.ok) {
        throw new Error(`HTTP ${response.status}`);
      }
      const data = (await response.json()) as ReleaseResult;
      setRelease(data.release);
      setSource(data.source);
      if (data.source === "fallback") {
        setErrorMessage(copy.download.retryHint);
      }
      if (!data.release.assets.some((item) => item.abi === selectedAbi)) {
        setSelectedAbi(data.release.assets[0]?.abi ?? "arm64-v8a");
      }
    } catch {
      setErrorMessage(copy.download.retryHint);
      setSource("fallback");
    } finally {
      setIsLoading(false);
    }
  };

  const handleCopySha = async () => {
    if (!selectedAsset?.sha256) return;
    try {
      await navigator.clipboard.writeText(selectedAsset.sha256);
      setCopiedSha(true);
      window.setTimeout(() => setCopiedSha(false), 2200);
    } catch {
      setCopiedSha(false);
    }
  };

  return (
    <section
      id="download"
      className="px-4 py-12 sm:px-6 md:py-20 lg:py-24"
      style={{ borderTop: "1px solid var(--hairline)" }}
    >
      <div className="mx-auto max-w-6xl">
        <div className="flex flex-col justify-between gap-6 lg:flex-row lg:items-end">
          <div className="min-w-0 max-w-[680px]">
            <p className="body-5 text-[var(--accent)]">{copy.download.sectionLabel}</p>
            <h2 className="heading-3 mt-2 text-[var(--text-primary)]">
              {copy.download.heading}
            </h2>
            <p className="body-3 mt-4 text-[var(--text-secondary)]">
              {copy.download.lead}
            </p>
          </div>

          <div className="flex flex-wrap items-center gap-3">
            <span className="caption-2 text-[var(--text-muted)]">
              {source === "github"
                ? copy.download.sourceLive
                : copy.download.sourceFallback}
            </span>
            <button
              type="button"
              onClick={handleRefreshRelease}
              disabled={isLoading}
              className="btn-secondary button-sm inline-flex min-h-11 items-center gap-2 rounded-xl px-4 py-2"
            >
              <ArrowsClockwise size={16} aria-hidden="true" />
              <span>
                {isLoading ? copy.download.checkingLatest : copy.download.checkLatest}
              </span>
            </button>
          </div>
        </div>

        {errorMessage && (
          <div
            role="status"
            className="lumyn-raised mt-6 flex items-center gap-2.5 rounded-2xl p-4 text-[var(--text-secondary)]"
          >
            <WarningCircle
              size={18}
              aria-hidden="true"
              className="shrink-0 text-[var(--accent)]"
            />
            <span className="body-6">{errorMessage}</span>
          </div>
        )}

        <div className="lumyn-card mt-8 rounded-2xl p-4 sm:p-6">
          <p className="body-5 text-[var(--text-muted)]">{copy.download.archLabel}</p>

          {isLoading ? (
            <div className="mt-4 space-y-3" aria-live="polite">
              <p className="body-5 text-[var(--text-secondary)]">
                {copy.download.loadingState}
              </p>
              <div className="grid grid-cols-1 gap-2.5 sm:grid-cols-2 md:grid-cols-3 lg:grid-cols-5">
                {["arm64", "univ", "arm32", "x64", "x86"].map((slot) => (
                  <div
                    key={slot}
                    className="h-12 rounded-xl opacity-60 lumyn-raised"
                  />
                ))}
              </div>
            </div>
          ) : release.assets.length === 0 ? (
            <div className="mt-4 rounded-xl p-6 lumyn-raised">
              <p className="body-3 text-[var(--text-secondary)]">
                {copy.download.emptyState}
              </p>
            </div>
          ) : (
            <>
              <div
                role="tablist"
                aria-label={copy.download.archLabel}
                className="mt-4 grid grid-cols-1 gap-2.5 sm:grid-cols-2 md:grid-cols-3 lg:grid-cols-5"
              >
                {release.assets.map((asset) => {
                  const selected = selectedAsset?.abi === asset.abi;
                  return (
                    <button
                      key={asset.abi}
                      type="button"
                      role="tab"
                      aria-selected={selected}
                      onClick={() => {
                        setSelectedAbi(asset.abi);
                        setCopiedSha(false);
                      }}
                      className="flex min-h-12 min-w-0 flex-col items-start justify-center rounded-xl px-3.5 py-2.5 text-left transition-colors duration-200"
                      style={{
                        backgroundColor: selected
                          ? "var(--accent-fill)"
                          : "var(--surface-raised)",
                        color: selected ? "var(--on-accent)" : "var(--text-primary)",
                        border: selected
                          ? "1px solid var(--accent-fill)"
                          : "1px solid var(--hairline)",
                      }}
                    >
                      <div className="flex w-full flex-wrap items-center justify-between gap-x-2 gap-y-1">
                        <span className="button-sm mono-tabular whitespace-nowrap">
                          {asset.label}
                        </span>
                        {asset.recommended && (
                          <span
                            className="caption-4 whitespace-nowrap rounded-md px-1.5 py-0.5"
                            style={{
                              backgroundColor: selected
                                ? "rgba(245, 250, 252, 0.16)"
                                : "var(--surface)",
                              color: selected ? "var(--on-accent)" : "var(--accent)",
                            }}
                          >
                            {copy.download.recommendedBadge}
                          </span>
                        )}
                      </div>
                      <span
                        className="caption-4 mono-tabular mt-1"
                        style={{
                          color: selected
                            ? "rgba(245, 250, 252, 0.85)"
                            : "var(--text-muted)",
                        }}
                      >
                        {asset.sizeFormatted}
                      </span>
                    </button>
                  );
                })}
              </div>

              {selectedAsset && (
                <div className="mt-6 grid gap-6 pt-6 lg:grid-cols-12 lg:items-center" style={{ borderTop: "1px solid var(--hairline)" }}>
                  <div className="min-w-0 lg:col-span-8">
                    <div className="grid grid-cols-2 gap-4 sm:grid-cols-[auto_auto_minmax(0,1fr)] sm:gap-x-8">
                      <div>
                        <div className="caption-4 text-[var(--text-muted)]">
                          {copy.download.versionLabel}
                        </div>
                        <div className="title-3 mono-tabular mt-1 whitespace-nowrap text-[var(--text-primary)]">
                          {release.tagName}
                        </div>
                      </div>
                      <div>
                        <div className="caption-4 text-[var(--text-muted)]">
                          {copy.download.sizeLabel}
                        </div>
                        <div className="title-3 mono-tabular mt-1 whitespace-nowrap text-[var(--text-primary)]">
                          {selectedAsset.sizeFormatted}
                        </div>
                      </div>
                      <div className="col-span-2 min-w-0 sm:col-span-1">
                        <div className="caption-4 text-[var(--text-muted)]">
                          {copy.download.fileNameLabel}
                        </div>
                        <div className="body-5 mono-tabular mt-1 break-all text-[var(--text-primary)]">
                          {selectedAsset.fileName}
                        </div>
                      </div>
                    </div>

                    <p className="body-3 mt-4 text-[var(--text-secondary)]">
                      {selectedAsset.description[locale]}
                    </p>

                    {selectedAsset.sha256 && (
                      <div className="mt-4">
                        <div className="caption-4 text-[var(--text-muted)]">
                          {copy.download.shaLabel}
                        </div>
                        <div className="lumyn-raised mt-1.5 flex flex-col gap-2 rounded-xl p-2.5 sm:flex-row sm:items-center sm:justify-between">
                          <div
                            tabIndex={0}
                            role="region"
                            aria-label={copy.download.shaLabel}
                            className="min-w-0 flex-1 overflow-x-auto rounded-lg px-1.5 py-1"
                          >
                            <code className="caption-4 mono-tabular whitespace-nowrap [overflow-wrap:normal] text-[var(--text-secondary)]">
                              {selectedAsset.sha256}
                            </code>
                          </div>
                          <button
                            type="button"
                            onClick={handleCopySha}
                            aria-live="polite"
                            className="btn-secondary caption-4 inline-flex min-h-11 w-full shrink-0 items-center justify-center gap-1.5 rounded-lg px-3 py-1.5 sm:w-auto"
                          >
                            {copiedSha ? (
                              <Check
                                size={15}
                                aria-hidden="true"
                                className="text-[var(--accent)]"
                              />
                            ) : (
                              <Copy size={15} aria-hidden="true" />
                            )}
                            <span>
                              {copiedSha ? copy.download.copiedSha : copy.download.copySha}
                            </span>
                          </button>
                        </div>
                      </div>
                    )}
                  </div>

                  <div className="flex flex-col gap-3 sm:flex-row sm:flex-wrap lg:col-span-4 lg:flex-col lg:items-end">
                    <a
                      href={selectedAsset.downloadUrl}
                      target="_blank"
                      rel="noopener noreferrer"
                      className="btn-primary button-md inline-flex min-h-12 w-full items-center justify-center gap-2 rounded-xl px-5 py-3 text-center no-underline sm:w-auto"
                    >
                      <DownloadSimple size={18} aria-hidden="true" />
                      <span>
                        {copy.download.downloadApk} ({selectedAsset.abi})
                      </span>
                    </a>

                    <a
                      href={release.htmlUrl || GITHUB_RELEASES_URL}
                      target="_blank"
                      rel="noopener noreferrer"
                      className="btn-secondary button-sm inline-flex min-h-11 w-full items-center justify-center gap-2 rounded-xl px-4 py-2 text-center no-underline sm:w-auto"
                    >
                      <span>{copy.download.viewAllReleases}</span>
                      <ArrowUpRight size={16} aria-hidden="true" />
                    </a>
                  </div>
                </div>
              )}
            </>
          )}
        </div>
      </div>
    </section>
  );
}
