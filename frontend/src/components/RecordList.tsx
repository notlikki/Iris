import React from 'react';
import { DownloadIcon, TrashIcon } from './icons';
import type { ImageRecordDto } from '../types';
import './RecordList.css';

interface Props {
  records: ImageRecordDto[];
  selectedId?: number;
  onSelect: (record: ImageRecordDto) => void;
  onDelete: (id: number) => void;
}

const formatDate = (isoString: string) => {
  try {
    const d = new Date(isoString);
    return d.toLocaleDateString([], { month: 'short', day: 'numeric' }) + ' · ' +
      d.toLocaleTimeString([], { hour: '2-digit', minute: '2-digit' });
  } catch {
    return isoString;
  }
};

export const RecordList: React.FC<Props> = ({ records, selectedId, onSelect, onDelete }) => {
  return (
    <section className="panel records" aria-labelledby="records-title">
      <div className="records-head">
        <h2 className="panel-title" id="records-title">Registered</h2>
        <span className="records-count">{records.length}</span>
      </div>

      {records.length === 0 ? (
        <p className="empty-state">Signed images will appear here.</p>
      ) : (
        <ul className="record-list">
          {records.map((record) => {
            const isSelected = record.id === selectedId;
            const layers = record.robustEmbedded
              ? `LSB ×${record.lsbCopies} · spread ×${record.robustCopies}`
              : `LSB ×${record.lsbCopies}`;

            return (
              <li key={record.id} className={`record-row ${isSelected ? 'is-selected' : ''}`}>
                <button
                  type="button"
                  className="record-main"
                  onClick={() => onSelect(record)}
                  aria-current={isSelected ? 'true' : undefined}
                >
                  <img src={record.signedUrl} alt="" className="record-thumb" loading="lazy" />
                  <span className="record-text">
                    <span className="record-name">{record.originalFilename}</span>
                    <span className="record-meta" title={`${layers} · ${formatDate(record.createdAt)}`}>
                      {record.width}×{record.height} · {layers}
                    </span>
                  </span>
                </button>

                <div className="record-actions">
                  <a
                    className="btn btn-icon"
                    href={record.downloadUrl}
                    download
                    aria-label={`Download original ${record.originalFilename}`}
                    title="Download original"
                  >
                    <DownloadIcon size={16} />
                  </a>
                  <button
                    type="button"
                    className="btn btn-icon is-danger"
                    onClick={() => onDelete(record.id)}
                    aria-label={`Delete record ${record.id}`}
                    title="Delete record"
                  >
                    <TrashIcon size={16} />
                  </button>
                </div>
              </li>
            );
          })}
        </ul>
      )}
    </section>
  );
};
