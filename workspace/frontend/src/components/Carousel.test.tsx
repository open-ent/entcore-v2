import { fireEvent, render } from "@testing-library/react";
import { describe, expect, it, vi } from "vitest";
import { Carousel, carouselImages } from "./Carousel";
import { WorkspaceElement } from "../lib/types";

vi.mock("react-i18next", () => ({ useTranslation: () => ({ t: (k: string) => k }) }));

const el = (id: string, type: string, eType: "file" | "folder" = "file") => ({ _id: id, name: id, eType, metadata: { "content-type": type } }) as unknown as WorkspaceElement;

describe("Carousel", () => {
  it("ne garde que les images", () => {
    expect(carouselImages([el("a", "image/png"), el("b", "application/pdf"), el("c", "image/jpeg"), el("d", "image/png", "folder")]).map((e) => e._id)).toEqual(["a", "c"]);
  });

  it("navigue d'une image à l'autre en boucle et par pastille", () => {
    render(<Carousel elements={[el("a", "image/png"), el("b", "application/pdf"), el("c", "image/png"), el("d", "image/png")]} />);
    const name = () => document.querySelector("[data-carousel-name]")?.textContent;
    expect(name()).toContain("a (1/3)");
    fireEvent.click(document.querySelector("[data-carousel-next]")!);
    expect(name()).toContain("c (2/3)");
    fireEvent.click(document.querySelectorAll("[data-carousel-dot]")[2]);
    expect(name()).toContain("d (3/3)");
    fireEvent.click(document.querySelector("[data-carousel-next]")!);
    expect(name()).toContain("a (1/3)");
    fireEvent.click(document.querySelector("[data-carousel-previous]")!);
    expect(name()).toContain("d (3/3)");
  });

  it("dossier sans image : message", () => {
    render(<Carousel elements={[el("b", "application/pdf")]} />);
    expect(document.querySelector("[data-carousel-empty]")).toBeInTheDocument();
  });
});
