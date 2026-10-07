export async function readResponse(response, fallback) {
  const text = await response.text();
  let data;
  try {
    data = text ? JSON.parse(text) : null;
  } catch {
    if (response.ok) throw new Error('The server returned an invalid JSON response.');
    throw new Error(text || fallback);
  }
  if (!response.ok) {
    throw new Error(data?.detail || data?.message || data?.error ||
      (typeof data === 'string' ? data : fallback));
  }
  return data;
}

export async function requestUploadUrl(baseUrl, fileName, contentType, size) {
  return readResponse(await fetch(`${baseUrl}/upload-url`, {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({ fileName, contentType, size }),
  }), 'Unable to initiate document upload.');
}

export async function uploadToS3(uploadUrl, file, contentType) {
  const response = await fetch(uploadUrl, {
    method: 'PUT', headers: { 'Content-Type': contentType }, body: file,
  });
  if (!response.ok) {
    throw new Error(`Direct S3 upload failed (${response.status}).`);
  }
}

export async function completeUpload(baseUrl, storageKey) {
  return readResponse(await fetch(`${baseUrl}/complete-upload`, {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({ storageKey }),
  }), 'Unable to complete document upload.');
}
