import React, { useRef, useState } from 'react';
import { UploadIcon, AlertCircleIcon, KeyIcon, SearchIcon } from './icons';
import './ActionPanel.css';

type Mode = 'sign' | 'verify';

interface Props {
  mode: Mode;
  onModeChange: (mode: Mode) => void;
  onSign: (file: File) => void;
  onVerify: (file: File) => void;
  isBusy: boolean;
}

const formatSize = (bytes: number) => {
  if (bytes < 1024) return `${bytes} B`;
  if (bytes < 1024 * 1024) return `${(bytes / 1024).toFixed(1)} KB`;
  return `${(bytes / (1024 * 1024)).toFixed(2)} MB`;
};

export const ActionPanel: React.FC<Props> = ({ mode, onModeChange, onSign, onVerify, isBusy }) => {
  const [selectedFile, setSelectedFile] = useState<File | null>(null);
  const [previewUrl, setPreviewUrl] = useState<string | null>(null);
  const [isDragOver, setIsDragOver] = useState(false);
  const [errorMsg, setErrorMsg] = useState<string | null>(null);
  const fileInputRef = useRef<HTMLInputElement>(null);

  const isSign = mode === 'sign';

  const validateAndSetFile = (file: File) => {
    setErrorMsg(null);
    const validTypes = ['image/jpeg', 'image/png', 'image/bmp'];
    if (!validTypes.includes(file.type) && !file.name.match(/\.(jpg|jpeg|png|bmp)$/i)) {
      setErrorMsg('That file is not a JPEG, PNG, or BMP.');
      return;
    }

    if (file.size > 15 * 1024 * 1024) {
      setErrorMsg('That file is larger than 15 MB.');
      return;
    }

    setSelectedFile(file);
    if (previewUrl) {
      URL.revokeObjectURL(previewUrl);
    }
    setPreviewUrl(URL.createObjectURL(file));
  };

  const handleDrop = (e: React.DragEvent) => {
    e.preventDefault();
    setIsDragOver(false);
    if (e.dataTransfer.files.length > 0) {
      validateAndSetFile(e.dataTransfer.files[0]);
    }
  };

  const handleFileChange = (e: React.ChangeEvent<HTMLInputElement>) => {
    if (e.target.files && e.target.files.length > 0) {
      validateAndSetFile(e.target.files[0]);
    }
    e.target.value = '';
  };

  const handleSubmit = (e: React.FormEvent) => {
    e.preventDefault();
    if (!selectedFile) {
      setErrorMsg('Select an image first.');
      return;
    }
    if (isSign) {
      onSign(selectedFile);
    } else {
      onVerify(selectedFile);
    }
  };

  const switchMode = (next: Mode) => {
    if (next === mode) return;
    setErrorMsg(null);
    onModeChange(next);
  };

  return (
    <section className="panel action" aria-labelledby="action-title">
      <div className="mode-switch" role="group" aria-label="Action">
        <button
          type="button"
          className={isSign ? 'is-active' : ''}
          onClick={() => switchMode('sign')}
          aria-pressed={isSign}
          disabled={isBusy}
        >
          <KeyIcon size={15} />
          <span>Sign</span>
        </button>
        <button
          type="button"
          className={!isSign ? 'is-active' : ''}
          onClick={() => switchMode('verify')}
          aria-pressed={!isSign}
          disabled={isBusy}
        >
          <SearchIcon size={15} />
          <span>Verify</span>
        </button>
      </div>

      <h2 className="panel-title" id="action-title">
        {isSign ? 'Sign an image' : 'Verify an image'}
      </h2>
      <p className="action-hint">
        {isSign
          ? 'A key is embedded invisibly across the pixels. The original is kept so it can be restored later.'
          : 'Upload a possibly tampered copy. The embedded key is read and the stored original is restored.'}
      </p>

      <form onSubmit={handleSubmit} className="action-form">
        <label
          className={`dropzone ${isDragOver ? 'is-dragging' : ''} ${selectedFile ? 'has-file' : ''}`}
          onDragOver={(e) => { e.preventDefault(); setIsDragOver(true); }}
          onDragLeave={() => setIsDragOver(false)}
          onDrop={handleDrop}
        >
          <input
            ref={fileInputRef}
            type="file"
            accept=".jpg,.jpeg,.png,.bmp,image/jpeg,image/png,image/bmp"
            onChange={handleFileChange}
            aria-label={isSign ? 'Choose an image to sign' : 'Choose an image to verify'}
          />

          {previewUrl ? (
            <div className="preview">
              <img src={previewUrl} alt={`Preview of ${selectedFile?.name ?? 'selected image'}`} />
              <div className="preview-meta">
                <span className="preview-name">{selectedFile?.name}</span>
                <span>{selectedFile ? formatSize(selectedFile.size) : ''} · Change</span>
              </div>
            </div>
          ) : (
            <>
              <UploadIcon size={20} className="dropzone-icon" />
              <span className="dropzone-title">Drop an image or browse</span>
              <span className="dropzone-sub">JPEG, PNG, or BMP · up to 15 MB</span>
            </>
          )}
        </label>

        {errorMsg && (
          <p className="field-error" role="alert">
            <AlertCircleIcon size={15} />
            <span>{errorMsg}</span>
          </p>
        )}

        <button type="submit" className="btn btn-primary btn-block submit" disabled={isBusy}>
          {isBusy ? (
            <>
              <span className="spinner" aria-hidden="true" />
              <span>{isSign ? 'Signing' : 'Verifying'}</span>
            </>
          ) : (
            <span>{isSign ? 'Sign image' : 'Verify and recover'}</span>
          )}
        </button>
      </form>
    </section>
  );
};
