"use strict";
function measure() {
  const rect = document.querySelector("#surface").getBoundingClientRect();
  document.querySelector("#metrics").textContent =
    `CSS viewport: ${innerWidth} × ${innerHeight}; DPR: ${devicePixelRatio}\n` +
    `Visual viewport: ${visualViewport?.width} × ${visualViewport?.height}\n` +
    `Test surface: ${rect.width} × ${rect.height}`;
}
addEventListener("resize", measure);
visualViewport?.addEventListener("resize", measure);
measure();
document.querySelector("#alert").onclick = () => {
  alert("Credential-free fixture alert");
  document.querySelector("#result").textContent = "Alert dismissed";
};
document.querySelector("#confirm").onclick = () => {
  document.querySelector("#result").textContent = `Confirm: ${confirm("Fixture confirm?")}`;
};
document.querySelector("#prompt").onclick = () => {
  const result = prompt("Fixture prompt — enter dummy text only", "fixture");
  // Do not retain or print entered text, even in this fixture.
  document.querySelector("#result").textContent = result === null ? "Prompt cancelled" : "Prompt accepted";
};
document.querySelector("#console").onclick = () => console.warn("Credential-free fixture console marker");
