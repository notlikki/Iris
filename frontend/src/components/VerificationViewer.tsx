import React, { useState } from 'react';
import { DownloadIcon, AlertTriangleIcon, ShieldCheckIcon } from './icons';
import type { VerificationResponseDto } from '../types';
import { ImageCompareSlider } from './ImageCompareSlider';
import './VerificationViewer.css';

interface Props {
  verification: VerificationResponseDto;
}

const METHOD_LABEL: Record<string, string> = {
  CHECKSUM: 'Exact original file',
  LSB: 'Embedded key · LSB',
  SPREAD: 'Embedded key · robust',
  PHASH: 'Perceptual match',
  NONE: 'No match'
};

export const VerificationViewer: React.FC<Props> = ({ verification }) => {
  const [view, setView] = useState<'compare' | 'difference'>('compare');
  const record = verification.record ?? null;
  const matched = verification.matched && record !== null;
  const methodLabel = METHOD_LABEL[verification.method] ?? verification.method;
  const similarityPct = verification.similarity != null
    ? (verification.similarity * 100).toFixed(1)
    : null;

  if (!matched || !record) {
    return (
      <section className="panel viewer" aria-labelledby="verify-title">
        <div className="viewer-head">
          <div className="viewer-title">
            <h2 className="viewer-name" id="verify-title">No matching record</h2>
            <p className="viewer-sub">
              <span className="status is-warn">
                <AlertTriangleIcon size={13} />
                Not recognized
              </span>
            </p>
          </div>
        </div>

        <div className="canvas">
          <div className="side-box single">
            <span className="side-label">Uploaded image</span>
            <img src={verification.candidateUrl} alt="Uploaded image that did not match" />
          </div>
          <p className="note">
            No embedded key could be recovered, and no stored record was close enough in perceptual space.
          </p>
        </div>
      </section>
    );
  }

  return (
    <section className="panel viewer" aria-labelledby="verify-title">
      <div className="viewer-head">
        <div className="viewer-title">
          <h2 className="viewer-name" id="verify-title">{record.originalFilename}</h2>
          <p className="viewer-sub">
            <span className="status is-ok">
              <ShieldCheckIcon size={13} />
              Original recovered
            </span>
            <span className="sep" aria-hidden="true">·</span>
            <span>{methodLabel}</span>
            {similarityPct && (
              <>
                <span className="sep" aria-hidden="true">·</span>
                <span>{similarityPct}% perceptual match</span>
              </>
            )}
          </p>
        </div>

        <div className="viewer-actions">
          {verification.diffUrl && (
            <div className="view-switch" role="group" aria-label="Preview mode">
              <button
                type="button"
                className={view === 'compare' ? 'is-active' : ''}
                onClick={() => setView('compare')}
                aria-pressed={view === 'compare'}
              >
                Compare
              </button>
              <button
                type="button"
                className={view === 'difference' ? 'is-active' : ''}
                onClick={() => setView('difference')}
                aria-pressed={view === 'difference'}
              >
                Difference
              </button>
            </div>
          )}

          <a className="btn btn-primary" href={verification.restoreUrl ?? record.downloadUrl} download>
            <DownloadIcon size={15} />
            <span>Restore original</span>
          </a>
        </div>
      </div>

      <div className="canvas">
        {view === 'difference' && verification.diffUrl ? (
          <div className="side-box single">
            <span className="side-label">Difference map</span>
            <img src={verification.diffUrl} alt="Difference map between the original and uploaded image" />
          </div>
        ) : (
          <ImageCompareSlider
            originalUrl={record.originalUrl}
            resultUrl={verification.candidateUrl}
            originalAlt={`Stored original ${record.originalFilename}`}
            resultAlt="Uploaded image"
            startLabel="Stored original"
            endLabel="Uploaded"
          />
        )}
      </div>

      <div className="viewer-foot">
        <dl className="meta-list">
          <div>
            <dt>Match</dt>
            <dd>{methodLabel}</dd>
          </div>
          <div>
            <dt>Perceptual distance</dt>
            <dd>{verification.perceptualDistance ?? '—'} / 64</dd>
          </div>
          <div>
            <dt>Pixels changed</dt>
            <dd>{verification.changedPercent != null ? `${verification.changedPercent.toFixed(1)}%` : '—'}</dd>
          </div>
          <div>
            <dt>Mean difference</dt>
            <dd>{verification.meanDiff != null ? `${verification.meanDiff.toFixed(1)} / 255` : '—'}</dd>
          </div>
          <div>
            <dt>Dimensions</dt>
            <dd>{verification.dimensionMatch ? 'Unchanged' : 'Resized for comparison'}</dd>
          </div>
          <div>
            <dt>Byte exact</dt>
            <dd>{verification.exactMatch ? 'Yes' : 'No'}</dd>
          </div>
        </dl>

        {verification.method === 'PHASH' && (
          <p className="note">
            The watermark could not be read from this copy, so the record was matched by perceptual hash.
          </p>
        )}

        <div className="token-block">
          <span className="checksum-label">Recovered key</span>
          <code className="token-value">{verification.token ?? record.token}</code>
        </div>

        <details className="integrity">
          <summary>Record details</summary>
          <div className="checksum">
            <div className="checksum-item">
              <span className="checksum-label">SHA-256 original</span>
              <span className="checksum-value">{record.originalChecksum}</span>
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
