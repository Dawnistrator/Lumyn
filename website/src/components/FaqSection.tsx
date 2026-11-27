"use client";

import React from "react";
import { CaretDown } from "@phosphor-icons/react";
import { useAppPreferences } from "@/components/Providers";

export function FaqSection() {
  const { copy } = useAppPreferences();

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
            {copy.faq.items.map((item, idx) => (
              <details
                key={item.question}
                open={idx === 0}
                className="group py-4 first:pt-0"
                style={{ borderColor: "var(--hairline)" }}
              >
                <summary className="flex min-h-12 w-full list-none items-center justify-between gap-4 rounded-xl py-2 text-left text-[var(--text-primary)] transition-colors duration-200 hover:text-[var(--accent)] [&::-webkit-details-marker]:hidden">
                  <span className="title-3">{item.question}</span>
                  <span
                    aria-hidden="true"
                    className="shrink-0 text-[var(--text-muted)] transition-transform duration-300 group-open:rotate-180"
                    style={{
                      transitionTimingFunction: "cubic-bezier(0.22, 1, 0.36, 1)",
                    }}
                  >
                    <CaretDown size={18} />
                  </span>
                </summary>

                <div className="pt-2 pb-2 pr-1 sm:pr-8">
                  <p className="body-3 break-words text-[var(--text-secondary)]">
                    {item.answer}
                  </p>
                </div>
              </details>
            ))}
          </div>
        </div>
      </div>
    </section>
  );
}
