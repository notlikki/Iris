import React, { useState } from 'react';
import { DownloadIcon, ShieldCheckIcon } from './icons';
import type { ImageRecordDto } from '../types';
import './RecordViewer.css';

interface Props {
  record: ImageRecordDto;
  onResign: (id: number) => void;
  isResigning: boolean;
}

const formatBytes = (bytes?: number) => {
  if (bytes === undefined || bytes === null) return '—';
  if (bytes < 1024) return `${bytes} B`;
  if (bytes < 1024 * 1024) return `${(bytes / 1024).toFixed(1)} KB`;
  return `${(bytes / (1024 * 1024)).toFixed(2)} MB`;
};

const formatDate = (isoString: string) => {
  try {
    const d = new Date(isoString);
    return d.toLocaleDateString([], { month: 'short', day: 'numeric' }) + ' · ' +
      d.toLocaleTimeString([], { hour: '2-digit', minute: '2-digit' });
  } catch {
    return isoString;
  }
};

export const RecordViewer: React.FC<Props> = ({ record, onResign, isResigning }) => {
  const [preview, setPreview] = useState<'signed' | 'original'>('signed');
  const protection = record.robustEmbedded
    ? `LSB ×${record.lsbCopies} · robust ×${record.robustCopies}`
    : `LSB ×${record.lsbCopies} · invisible`;

  return (
    <section className="panel viewer" aria-labelledby="record-title">
      <div className="viewer-head">
        <div className="viewer-title">
          <h2 className="viewer-name" id="record-title">{record.originalFilename}</h2>
          <p className="viewer-sub">
            <span className="status">
              <ShieldCheckIcon size={13} />
              Signed
            </span>
            <span className="sep" aria-hidden="true">·</span>
            <span>{formatDate(record.createdAt)}</span>
          </p>
        </div>

        <div className="viewer-actions">
          <div className="view-switch" role="group" aria-label="Preview image">
            <button
              type="button"
              className={preview === 'signed' ? 'is-active' : ''}
              onClick={() => setPreview('signed')}
              aria-pressed={preview === 'signed'}
            >
              Signed
            </button>
            <button
              type="button"
              className={preview === 'original' ? 'is-active' : ''}
              onClick={() => setPreview('original')}
              aria-pressed={preview === 'original'}
            >
              Original
            </button>
          </div>

          <button
            type="button"
            className="btn"
            onClick={() => onResign(record.id)}
            disabled={isResigning}
            title="Re-embed the stored original with the current watermark strength"
          >
            {isResigning ? 'Re-signing…' : 'Re-sign'}
          </button>

          <a className="btn" href={record.signedDownloadUrl} download>
            <DownloadIcon size={15} />
            <span>Download signed</span>
          </a>

          <a className="btn btn-primary" href={record.downloadUrl} download>
            <DownloadIcon size={15} />
            <span>Restore original</span>
          </a>
        </div>
      </div>

      <div className="canvas">
        <div className="side-box single">
          <span className="side-label">{preview === 'signed' ? 'Signed image' : 'Stored original'}</span>
          <img
            src={preview === 'signed' ? record.signedUrl : record.originalUrl}
            alt={preview === 'signed' ? `Signed ${record.originalFilename}` : `Original ${record.originalFilename}`}
          />
        </div>
      </div>

      <div className="viewer-foot">
        <dl className="meta-list">
          <div>
            <dt>Dimensions</dt>
            <dd>{record.width} × {record.height} px</dd>
          </div>
          <div>
            <dt>Original size</dt>
            <dd>{formatBytes(record.originalSize)}</dd>
          </div>
          <div>
            <dt>Signed size</dt>
            <dd>{formatBytes(record.signedSize)}</dd>
          </div>
          <div>
            <dt>Protection</dt>
            <dd>{protection}</dd>
          </div>
        </dl>

        <div className="token-block">
          <span className="checksum-label">Embedded key</span>
          <code className="token-value">{record.token}</code>
        </div>

        <details className="integrity">
          <summary>Checksums</summary>
          <div className="checksum">
            <div className="checksum-item">
              <span className="checksum-label">SHA-256 original</span>
              <span className="checksum-value">{record.originalChecksum}</span>
            </div>
            <div className="checksum-item">
              <span className="checksum-label">SHA-256 signed</span>
              <span className="checksum-value">{record.signedChecksum}</span>
            </div>
            <div className="checksum-item">
              <span className="checksum-label">Perceptual hash</span>
              <span className="checksum-value">{record.perceptualHash}</span>
            </div>
          </div>
        </details>
      </div>
    </section>
  );
};
