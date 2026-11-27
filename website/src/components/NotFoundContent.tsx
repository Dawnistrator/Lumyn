"use client";

import React from "react";
import Link from "next/link";
import { ArrowLeft } from "@phosphor-icons/react";
import { Navbar } from "@/components/Navbar";
import { Footer } from "@/components/Footer";
import { useAppPreferences } from "@/components/Providers";

export function NotFoundContent() {
  const { copy } = useAppPreferences();

  return (
    <div className="flex min-h-dvh flex-col">
      <Navbar isSubPage />
      <main
        id="main-content"
        tabIndex={-1}
        className="flex flex-1 items-center px-4 py-12 sm:px-6 md:py-16"
      >
        <div className="mx-auto w-full max-w-xl">
          <p className="caption-2 mono-tabular text-[var(--accent)]">
            {copy.notFound.code}
          </p>
          <h1 className="heading-2 mt-2 text-[var(--text-primary)]">
            {copy.notFound.title}
          </h1>
          <p className="body-3 mt-4 text-[var(--text-secondary)]">
            {copy.notFound.description}
          </p>
          <div className="mt-8">
            <Link
              href="/"
              className="btn-primary button-md inline-flex min-h-12 w-full items-center justify-center gap-2 rounded-xl px-5 py-3 text-center no-underline sm:w-auto"
            >
              <ArrowLeft size={18} aria-hidden="true" />
              <span>{copy.notFound.backHome}</span>
            </Link>
          </div>
        </div>
      </main>
      <Footer hideFinalCta />
    </div>
  );
}
