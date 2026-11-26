import React from "react";
import { Navbar } from "@/components/Navbar";
import { Hero } from "@/components/Hero";
import { SignalPathExplorer } from "@/components/SignalPathExplorer";
import { CapabilitiesShowcase } from "@/components/CapabilitiesShowcase";
import { TaglineReveal } from "@/components/TaglineReveal";
import { PrivacyProof } from "@/components/PrivacyProof";
import { DownloadCenter } from "@/components/DownloadCenter";
import { FaqSection } from "@/components/FaqSection";
import { Footer } from "@/components/Footer";
import { dictionaries } from "@/lib/i18n";
import { fetchLatestRelease, GITHUB_REPO_URL } from "@/lib/releases";

export const revalidate = 3600;

export default async function HomePage() {
  const { release, source } = await fetchLatestRelease();

  const softwareSchema = {
    "@context": "https://schema.org",
    "@type": "SoftwareApplication",
    name: "Lumyn",
    operatingSystem: "Android 8.0+",
    applicationCategory: "MultimediaApplication",
    softwareVersion: release.tagName.replace(/^v/i, ""),
    downloadUrl: release.htmlUrl,
    codeRepository: GITHUB_REPO_URL,
    description:
      "Offline Hi-Res Android music player for FLAC, WAV, ALAC, and MP3 files with 32-bit float DSP, bit-perfect USB DAC output, and zero network permissions.",
  };

  const faqSchema = {
    "@context": "https://schema.org",
    "@type": "FAQPage",
    mainEntity: dictionaries.en.faq.items.map((item) => ({
      "@type": "Question",
      name: item.question,
      acceptedAnswer: {
        "@type": "Answer",
        text: item.answer,
      },
    })),
  };

  return (
    <div className="flex min-h-screen flex-col">
      <Navbar />
      <main id="main-content" className="flex-1">
        <Hero latestTag={release.tagName} />
        <SignalPathExplorer />
        <CapabilitiesShowcase />
        <TaglineReveal />
        <PrivacyProof />
        <DownloadCenter initialRelease={release} initialSource={source} />
        <FaqSection />
      </main>
      <Footer latestTag={release.tagName} />

      <script
        type="application/ld+json"
        dangerouslySetInnerHTML={{ __html: JSON.stringify(softwareSchema) }}
      />
      <script
        type="application/ld+json"
        dangerouslySetInnerHTML={{ __html: JSON.stringify(faqSchema) }}
      />
    </div>
  );
}
