
export const getBaseURI = () => {
    return document.querySelector('base')?.getAttribute('href') || '';
}

export const withBaseURI = (path: string) => {
    if (path.startsWith('http://') || path.startsWith('https://')) {
        return path; // this must be an external URL, so do nothing
    }
    return (getBaseURI() + path).replace(/\/+/g, '/');
}
