"use client";

import React, { useState } from "react";
import { CaretDown } from "@phosphor-icons/react";
import { useAppPreferences } from "@/components/Providers";

export function FaqSection() {
  const { copy } = useAppPreferences();
  const [openIndex, setOpenIndex] = useState<number | null>(0);

  return (
    <section
      id="faq"
      className="px-4 py-12 sm:px-6 md:py-20 lg:py-24"
      style={{ borderTop: "1px solid var(--hairline)" }}
    >
      <div className="mx-auto grid max-w-6xl gap-8 lg:grid-cols-12 lg:gap-10">
        <div className="min-w-0 lg:col-span-4">
          <p className="body-5 text-[var(--accent)]">{copy.faq.sectionLabel}</p>
          <h2 className="heading-3 mt-2 text-[var(--text-primary)]">
            {copy.faq.heading}
          </h2>
        </div>

        <div className="min-w-0 lg:col-span-8">
          <div className="divide-y" style={{ borderColor: "var(--hairline)" }}>
            {copy.faq.items.map((item, idx) => {
              const isOpen = openIndex === idx;
              const panelId = `faq-panel-${idx}`;
              return (
                <div
                  key={item.question}
                  className="py-4 first:pt-0"
                  style={{ borderColor: "var(--hairline)" }}
                >
                  <button
                    type="button"
                    aria-expanded={isOpen}
                    aria-controls={panelId}
                    onClick={() => setOpenIndex(isOpen ? null : idx)}
                    className="flex min-h-12 w-full items-center justify-between gap-4 rounded-xl py-2 text-left text-[var(--text-primary)] transition-colors duration-200 hover:text-[var(--accent)]"
                  >
                    <span className="title-3">{item.question}</span>
                    <CaretDown
                      size={18}
                      aria-hidden="true"
                      className="shrink-0 text-[var(--text-muted)] transition-transform duration-300"
                      style={{
                        transform: isOpen ? "rotate(180deg)" : "rotate(0deg)",
                        transitionTimingFunction: "cubic-bezier(0.22, 1, 0.36, 1)",
                      }}
                    />
                  </button>

                  {isOpen && (
                    <div id={panelId} className="pt-2 pb-2 pr-1 sm:pr-8">
                      <p className="body-3 break-words text-[var(--text-secondary)]">
                        {item.answer}
                      </p>
                    </div>
                  )}
                </div>
              );
            })}
          </div>
        </div>
      </div>
    </section>
  );
}
