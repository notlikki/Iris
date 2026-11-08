import React, { useEffect, useState, useCallback } from 'react';
import {
  RefreshIcon,
  TerminalIcon,
  CloseIcon,
  AlertCircleIcon,
  CheckCircleIcon,
  KeyIcon,
  SearchIcon
} from './components/icons';
import type { ImageRecordDto, VerificationResponseDto, AuditEventDto } from './types';
import { ActionPanel } from './components/ActionPanel';
import { RecordList } from './components/RecordList';
import { RecordViewer } from './components/RecordViewer';
import { VerificationViewer } from './components/VerificationViewer';
import './App.css';

type Mode = 'sign' | 'verify';

const METHOD_LABEL: Record<string, string> = {
  CHECKSUM: 'exact original file',
  LSB: 'embedded key (LSB)',
  SPREAD: 'embedded key (robust)',
  PHASH: 'perceptual hash'
};

export const App: React.FC = () => {
  const [mode, setMode] = useState<Mode>('sign');
  const [records, setRecords] = useState<ImageRecordDto[]>([]);
  const [selectedRecord, setSelectedRecord] = useState<ImageRecordDto | null>(null);
  const [verification, setVerification] = useState<VerificationResponseDto | null>(null);
  const [showVerification, setShowVerification] = useState(false);
  const [isBusy, setIsBusy] = useState(false);
  const [resigningId, setResigningId] = useState<number | null>(null);
  const [errorMessage, setErrorMessage] = useState<string | null>(null);
  const [successMessage, setSuccessMessage] = useState<string | null>(null);
  const [showAuditLogs, setShowAuditLogs] = useState(false);
  const [auditLogs, setAuditLogs] = useState<AuditEventDto[]>([]);

  const fetchRecords = useCallback(async () => {
    try {
      const response = await fetch('/api/records');
      if (!response.ok) {
        throw new Error('Failed to load registered images');
      }
      const data: ImageRecordDto[] = await response.json();
      setRecords(data);
      setSelectedRecord((current) => current
        ? data.find((record) => record.id === current.id) ?? data[0] ?? null
        : data[0] ?? null);
    } catch (err) {
      setErrorMessage(err instanceof Error ? err.message : 'Failed to load registered images');
    }
  }, []);

  const fetchAuditLogs = async () => {
    try {
      const response = await fetch('/api/audit-logs');
      if (response.ok) {
        const logs: AuditEventDto[] = await response.json();
        setAuditLogs(logs);
      }
    } catch (err) {
      console.error('Error fetching audit logs:', err);
    }
  };

  useEffect(() => {
    fetchRecords();
  }, [fetchRecords]);

  const readError = async (response: Response, fallback: string) => {
    const payload = await response.json().catch(() => null);
    return payload?.message || `${fallback} (${response.status})`;
  };

  const handleSign = async (file: File, robust: boolean) => {
    setIsBusy(true);
    setErrorMessage(null);
    setSuccessMessage(null);

    const formData = new FormData();
    formData.append('file', file);
    formData.append('protection', robust ? 'robust' : 'invisible');

    try {
      const response = await fetch('/api/records', { method: 'POST', body: formData });
      if (!response.ok) {
        throw new Error(await readError(response, 'Signing failed'));
      }

      const record: ImageRecordDto = await response.json();
      setSelectedRecord(record);
      setShowVerification(false);
      setSuccessMessage(record.robustEmbedded
        ? `Image signed with robust protection. Key ${record.token.slice(0, 8)}… embedded.`
        : `Image signed invisibly. Key ${record.token.slice(0, 8)}… embedded.`);
      await fetchRecords();
      if (showAuditLogs) {
        fetchAuditLogs();
      }
    } catch (err) {
      setErrorMessage(err instanceof Error ? err.message : 'Signing failed. Check the file and try again.');
    } finally {
      setIsBusy(false);
    }
  };

  const handleVerify = async (file: File) => {
    setIsBusy(true);
    setErrorMessage(null);
    setSuccessMessage(null);

    const formData = new FormData();
    formData.append('file', file);

    try {
      const response = await fetch('/api/verify', { method: 'POST', body: formData });
      if (!response.ok) {
        throw new Error(await readError(response, 'Verification failed'));
      }

      const result: VerificationResponseDto = await response.json();
      setVerification(result);
      setShowVerification(true);
      if (result.matched && result.record) {
        setSelectedRecord(result.record);
        setSuccessMessage(result.method === 'PHASH'
          ? 'Possible visual match found. The hidden key could not be recovered.'
          : `Original identified via ${METHOD_LABEL[result.method] ?? result.method}.`);
      }
      if (showAuditLogs) {
        fetchAuditLogs();
      }
    } catch (err) {
      setErrorMessage(err instanceof Error ? err.message : 'Verification failed. Check the file and try again.');
    } finally {
      setIsBusy(false);
    }
  };

  const handleSelectRecord = (record: ImageRecordDto) => {
    setSelectedRecord(record);
    setShowVerification(false);
  };

  const handleModeChange = (next: Mode) => {
    setMode(next);
    setShowVerification(false);
    if (next === 'verify') {
      setSelectedRecord(null);
      setVerification(null);
    } else {
      setSelectedRecord(records[0] ?? null);
    }
  };

  const handleResign = async (id: number) => {
    setResigningId(id);
    setErrorMessage(null);
    setSuccessMessage(null);

    try {
      const response = await fetch(`/api/records/${id}/resign`, { method: 'POST' });
      if (!response.ok) {
        throw new Error(await readError(response, 'Re-sign failed'));
      }

      const updated: ImageRecordDto = await response.json();
      setSelectedRecord(updated);
      setShowVerification(false);
      setSuccessMessage(`Record #${id} re-signed with the current watermark strength.`);
      await fetchRecords();
      if (showAuditLogs) {
        fetchAuditLogs();
      }
    } catch (err) {
      setErrorMessage(err instanceof Error ? err.message : 'Could not re-sign the record.');
    } finally {
      setResigningId(null);
    }
  };

  const handleDeleteRecord = async (id: number) => {
    if (!window.confirm(`Delete record #${id} and its stored original?`)) {
      return;
    }

    try {
      const response = await fetch(`/api/records/${id}`, { method: 'DELETE' });
      if (!response.ok) {
        throw new Error(await readError(response, 'Delete failed'));
      }

      setSuccessMessage(`Deleted record #${id}.`);
      setShowVerification(false);
      setVerification(null);
      setSelectedRecord(null);
      await fetchRecords();
      if (showAuditLogs) {
        fetchAuditLogs();
      }
    } catch (err) {
      setErrorMessage(err instanceof Error ? err.message : 'Could not delete the record.');
    }
  };

  const toggleAuditLogs = () => {
    const next = !showAuditLogs;
    setShowAuditLogs(next);
    if (next) {
      fetchAuditLogs();
    }
  };

  return (
    <div className="app">
      <header className="topbar">
        <div className="brand">
          <span className="brand-mark" aria-hidden="true">I</span>
          <span className="brand-name">IRIS</span>
          <span className="brand-note">Steganographic image recovery</span>
        </div>

        <div className="topbar-actions">
          <button type="button" className="btn btn-quiet" onClick={fetchRecords}>
            <RefreshIcon size={15} />
            <span>Refresh</span>
          </button>
          <button
            type="button"
            className={`btn ${showAuditLogs ? 'is-active' : 'btn-quiet'}`}
            onClick={toggleAuditLogs}
            aria-expanded={showAuditLogs}
          >
            <TerminalIcon size={15} />
            <span>Audit log</span>
          </button>
        </div>
      </header>

      {errorMessage && (
        <div className="notice notice-error" role="alert">
          <AlertCircleIcon size={16} />
          <span>{errorMessage}</span>
          <button
            type="button"
            className="notice-dismiss"
            onClick={() => setErrorMessage(null)}
            aria-label="Dismiss error"
          >
            <CloseIcon size={15} />
          </button>
        </div>
      )}

      {successMessage && (
        <div className="notice notice-success" role="status">
          <CheckCircleIcon size={16} />
          <span>{successMessage}</span>
          <button
            type="button"
            className="notice-dismiss"
            onClick={() => setSuccessMessage(null)}
            aria-label="Dismiss message"
          >
            <CloseIcon size={15} />
          </button>
        </div>
      )}

      {showAuditLogs && (
        <section className="panel audit" aria-label="Audit log">
          <div className="audit-head">
            <h2 className="audit-title">Audit log</h2>
            <span className="audit-note">No file paths or image contents are recorded</span>
          </div>
          <div className="audit-scroll">
            <table className="audit-table">
              <thead>
                <tr>
                  <th>Time</th>
                  <th>Event</th>
                  <th>Record</th>
                  <th>Summary</th>
                </tr>
              </thead>
              <tbody>
                {auditLogs.length === 0 ? (
                  <tr>
                    <td colSpan={4} className="audit-empty">No audit events recorded yet.</td>
                  </tr>
                ) : (
                  auditLogs.map((log) => (
                    <tr key={log.id}>
                      <td className="font-mono">{new Date(log.timestamp).toLocaleTimeString()}</td>
                      <td className="event">{log.eventType}</td>
                      <td>{log.recordId ? `#${log.recordId}` : '—'}</td>
                      <td>{log.summary}</td>
                    </tr>
                  ))
                )}
              </tbody>
            </table>
          </div>
        </section>
      )}

      <main className="workspace">
        <div className="rail">
          <ActionPanel
            mode={mode}
            onModeChange={handleModeChange}
            onSign={handleSign}
            onVerify={handleVerify}
            isBusy={isBusy}
          />

          <RecordList
            records={records}
            selectedId={selectedRecord?.id}
            onSelect={handleSelectRecord}
            onDelete={handleDeleteRecord}
          />
        </div>

        <div className="stage">
          {showVerification && verification ? (
            <VerificationViewer verification={verification} />
          ) : selectedRecord ? (
            <RecordViewer
              record={selectedRecord}
              onResign={handleResign}
              isResigning={resigningId === selectedRecord.id}
            />
          ) : (
            <section className="panel empty-viewer">
              <div>
                {mode === 'sign' ? (
                  <KeyIcon size={22} className="empty-viewer-icon" />
                ) : (
                  <SearchIcon size={22} className="empty-viewer-icon" />
                )}
                <h2>{mode === 'sign' ? 'No image signed yet' : 'No verification yet'}</h2>
                <p>
                  {mode === 'sign'
                    ? 'Sign an image to embed a key invisibly and keep its original for recovery.'
                    : 'Upload a possibly tampered image to read its key and recover the stored original.'}
                </p>
              </div>
            </section>
          )}
        </div>
      </main>

      <footer className="site-footer">
        <span>Local demonstration · no accounts or authentication · originals are never modified</span>
        <span>Java 21 · Spring Boot 3 · React</span>
      </footer>
    </div>
  );
};

export default App;
