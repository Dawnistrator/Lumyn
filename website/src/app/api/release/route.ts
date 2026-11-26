import { NextResponse } from "next/server";
import { fetchLatestRelease } from "@/lib/releases";

export const dynamic = "force-dynamic";

export async function GET() {
  const result = await fetchLatestRelease(true);
  return NextResponse.json(result);
}
