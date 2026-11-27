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
import { getLatestRelease, GITHUB_REPO_URL } from "@/lib/releases";

export const revalidate = 3600;

export default async function HomePage() {
  const { release, windowsRelease, source } = await getLatestRelease();

  const structuredGraph = {
    "@context": "https://schema.org",
    "@graph": [
      {
        "@type": "SoftwareApplication",
        name: "Lunaria",
        operatingSystem: ["Android 8.0+", "Windows 10/11 (x64)"],
        applicationCategory: "MultimediaApplication",
        softwareVersion: release.tagName.replace(/^v/i, ""),
        downloadUrl: release.htmlUrl,
        codeRepository: GITHUB_REPO_URL,
        license: "https://www.gnu.org/licenses/gpl-3.0.html",
        isAccessibleForFree: true,
        offers: {
          "@type": "Offer",
          price: "0",
          priceCurrency: "USD",
        },
        description:
          "Offline Hi-Res music player for Android and Windows supporting FLAC, WAV, ALAC, and MP3 files with floating-point RBJ biquad DSP, bit-perfect output routing, and zero network permissions.",
      },
      {
        "@type": "FAQPage",
        mainEntity: dictionaries.en.faq.items.map((item) => ({
          "@type": "Question",
          name: item.question,
          acceptedAnswer: {
            "@type": "Answer",
            text: item.answer,
          },
        })),
      },
    ],
  };

  return (
    <div className="flex min-h-dvh flex-col">
      <Navbar latestTag={release.tagName} />
      <main id="main-content" tabIndex={-1} className="flex-1">
        <Hero latestTag={release.tagName} />
        <SignalPathExplorer />
        <CapabilitiesShowcase />
        <TaglineReveal />
        <PrivacyProof latestTag={release.tagName} />
        <DownloadCenter
          initialRelease={release}
          initialWindowsRelease={windowsRelease}
          initialSource={source}
        />
        <FaqSection />
      </main>
      <Footer latestTag={release.tagName} />

      <script
        type="application/ld+json"
        dangerouslySetInnerHTML={{
          __html: JSON.stringify(structuredGraph).replace(/</g, "\\u003c"),
        }}
      />
    </div>
  );
}
