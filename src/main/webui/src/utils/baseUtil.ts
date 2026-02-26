export const getBaseURI = () => {
  return document.querySelector("base")?.getAttribute("href") || "";
};

export const joinUrl = (base: string, path: string) => {
  return `${base.replace(/\/+$/, "")}/${path.replace(/^\/+/, "")}`;
};

export const withBaseURI = (path: string) => {
  if (path.startsWith("http://") || path.startsWith("https://")) {
    return path; // this must be an external URL, so do nothing
  }
  return joinUrl(getBaseURI(), path);
};
