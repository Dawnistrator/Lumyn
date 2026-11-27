"use client";

import React, { useRef, useState } from "react";
import { CheckCircle, Cpu, Disc, Sliders, SpeakerHigh } from "@phosphor-icons/react";
import { useAppPreferences } from "@/components/Providers";

type SignalMode = "dsp" | "purist";
const SIGNAL_MODES: readonly SignalMode[] = ["dsp", "purist"];

export function SignalPathExplorer() {
  const { copy } = useAppPreferences();
  const [mode, setMode] = useState<SignalMode>("dsp");
  const [activeIndex, setActiveIndex] = useState(2);
  const tabRefs = useRef<Array<HTMLButtonElement | null>>([]);

  const currentMode = copy.signal.modes[mode];
  const currentStage = currentMode.stages[activeIndex] ?? currentMode.stages[0];

  const handleModeChange = (nextMode: SignalMode) => {
    setMode(nextMode);
    setActiveIndex(2);
  };

  const handleTabKeyDown = (event: React.KeyboardEvent<HTMLButtonElement>, idx: number) => {
    let nextIdx: number | null = null;
    if (event.key === "ArrowRight") {
      nextIdx = (idx + 1) % SIGNAL_MODES.length;
    } else if (event.key === "ArrowLeft") {
      nextIdx = (idx - 1 + SIGNAL_MODES.length) % SIGNAL_MODES.length;
    } else if (event.key === "Home") {
      nextIdx = 0;
    } else if (event.key === "End") {
      nextIdx = SIGNAL_MODES.length - 1;
    }
    if (nextIdx === null) return;
    event.preventDefault();
    const nextMode = SIGNAL_MODES[nextIdx];
    handleModeChange(nextMode);
    tabRefs.current[nextIdx]?.focus();
  };

  const icons = [Disc, Cpu, Sliders, SpeakerHigh];

  return (
    <section
      id="signal-path"
      className="px-4 py-12 sm:px-6 md:py-20 lg:py-24"
      style={{ borderTop: "1px solid var(--hairline)" }}
    >
      <div className="mx-auto max-w-6xl">
        <div className="grid gap-6 lg:grid-cols-12 lg:items-end">
          <div className="min-w-0 lg:col-span-7">
            <p className="body-5 text-[var(--accent)]">{copy.signal.sectionLabel}</p>
            <h2 className="heading-3 mt-2 max-w-[680px] text-[var(--text-primary)]">
              {copy.signal.heading}
            </h2>
            <p className="body-3 mt-4 max-w-[640px] text-[var(--text-secondary)]">
              {copy.signal.lead}
            </p>
          </div>

          <div className="flex min-w-0 lg:col-span-5 lg:justify-end">
            <div
              role="tablist"
              aria-label={copy.signal.sectionLabel}
              className="inline-flex w-full max-w-full flex-wrap rounded-2xl p-1 sm:w-auto sm:max-w-md"
              style={{
                backgroundColor: "var(--surface)",
                border: "1px solid var(--ui-border)",
              }}
            >
              {SIGNAL_MODES.map((item, idx) => {
                const selected = mode === item;
                return (
                  <button
                    key={item}
                    ref={(el) => {
                      tabRefs.current[idx] = el;
                    }}
                    id={`signal-tab-${item}`}
                    type="button"
                    role="tab"
                    aria-selected={selected}
                    aria-controls="signal-panel"
                    tabIndex={selected ? 0 : -1}
                    onClick={() => handleModeChange(item)}
                    onKeyDown={(e) => handleTabKeyDown(e, idx)}
                    className="button-sm min-h-11 min-w-0 flex-1 rounded-xl px-3 py-2 text-center leading-snug transition-colors duration-200 sm:flex-none sm:px-4"
                    style={{
                      backgroundColor: selected ? "var(--accent-fill)" : "transparent",
                      color: selected ? "var(--on-accent)" : "var(--text-secondary)",
                    }}
                  >
                    {copy.signal.modes[item].tabLabel}
                  </button>
                );
              })}
            </div>
          </div>
        </div>

        <div id="signal-panel" role="tabpanel" aria-labelledby={`signal-tab-${mode}`}>
          <p className="body-5 mt-6 text-[var(--text-muted)]">{currentMode.summary}</p>

          <div className="mt-6 grid gap-3 sm:grid-cols-2 lg:grid-cols-4">
            {currentMode.stages.map((stage, idx) => {
              const Icon = icons[idx] ?? Cpu;
              const selected = idx === activeIndex;
              return (
                <button
                  key={stage.id}
                  type="button"
                  onClick={() => setActiveIndex(idx)}
                  aria-pressed={selected}
                  className="flex min-h-[104px] w-full min-w-0 flex-col items-start justify-between rounded-2xl p-3.5 text-left transition-colors duration-200 sm:min-h-[124px] sm:p-4"
                  style={{
                    backgroundColor: selected ? "var(--surface-raised)" : "var(--surface)",
                    border: selected
                      ? "1px solid var(--accent)"
                      : "1px solid var(--ui-border)",
                  }}
                >
                  <div className="flex w-full items-center justify-between gap-2">
                    <span className="caption-4 mono-tabular text-[var(--text-muted)]">
                      {stage.step}
                    </span>
                    <Icon
                      size={18}
                      aria-hidden="true"
                      className={
                        selected ? "text-[var(--accent)]" : "text-[var(--text-muted)]"
                      }
                    />
                  </div>

                  <div className="mt-4 w-full min-w-0">
                    <div className="title-3 text-[var(--text-primary)]">{stage.label}</div>
                    <div className="caption-4 mono-tabular mt-1 text-[var(--text-secondary)]">
                      {stage.spec}
                    </div>
                  </div>
                </button>
              );
            })}
          </div>

          <div
            role="region"
            aria-live="polite"
            className="lunaria-card mt-4 min-w-0 rounded-2xl p-4 sm:p-6"
          >
            <div className="flex flex-wrap items-center gap-3">
              <CheckCircle
                size={18}
                aria-hidden="true"
                className="shrink-0 text-[var(--accent)]"
              />
              <span className="title-3 text-[var(--text-primary)]">
                {currentStage.step}. {currentStage.label}
              </span>
              <span className="caption-4 mono-tabular max-w-full break-words rounded-lg px-2.5 py-1 text-[var(--text-secondary)] lunaria-raised">
                {currentStage.spec}
              </span>
            </div>
            <p className="body-3 mt-3 max-w-[720px] text-[var(--text-secondary)]">
              {currentStage.detail}
            </p>
          </div>
        </div>
      </div>
    </section>
  );
}
