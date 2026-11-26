import type { Metadata } from "next";
import { PrivacyContent } from "@/components/PrivacyContent";

export const metadata: Metadata = {
  title: "Privacy Policy",
  description:
    "Lumyn declares zero network permissions and stores all library metadata, playlists, and EQ settings locally on your Android device.",
  alternates: {
    canonical: "/privacy",
  },
};

export default function PrivacyPage() {
  return <PrivacyContent />;
}
