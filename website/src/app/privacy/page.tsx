import type { Metadata } from "next";
import { PrivacyContent } from "@/components/PrivacyContent";

export const metadata: Metadata = {
  title: "Privacy Policy",
  description:
    "Lunaria declares zero network permissions on Android and compiles with zero HTTP dependencies on Windows, storing all library metadata locally.",
  alternates: {
    canonical: "/privacy",
  },
  openGraph: {
    type: "article",
    url: "/privacy",
    title: "Privacy Policy | Lunaria",
    description:
      "Offline by architecture on Android and Windows: zero network permissions, zero telemetry, verified on every CI build.",
  },
  twitter: {
    card: "summary_large_image",
    title: "Privacy Policy | Lunaria",
    description:
      "Offline by architecture: zero network permissions and local-only storage.",
  },
};

export default function PrivacyPage() {
  return <PrivacyContent />;
}
