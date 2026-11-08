import React, { useCallback, useRef, useState } from 'react';
import './ImageCompareSlider.css';

interface Props {
  originalUrl: string;
  resultUrl: string;
  originalAlt?: string;
  resultAlt?: string;
  startLabel?: string;
  endLabel?: string;
}

export const ImageCompareSlider: React.FC<Props> = ({
  originalUrl,
  resultUrl,
  originalAlt = 'Original image',
  resultAlt = 'Processed image',
  startLabel = 'Original',
  endLabel = 'Processed'
}) => {
  const [position, setPosition] = useState(50);
  const draggingRef = useRef(false);
  const containerRef = useRef<HTMLDivElement>(null);

  const updateFromClientX = useCallback((clientX: number) => {
    const el = containerRef.current;
    if (!el) return;
    const rect = el.getBoundingClientRect();
    if (rect.width === 0) return;
    const next = ((clientX - rect.left) / rect.width) * 100;
    setPosition(Math.min(100, Math.max(0, next)));
  }, []);

  const handlePointerDown = (e: React.PointerEvent<HTMLDivElement>) => {
    draggingRef.current = true;
    e.currentTarget.setPointerCapture(e.pointerId);
    updateFromClientX(e.clientX);
  };

  const handlePointerMove = (e: React.PointerEvent<HTMLDivElement>) => {
    if (draggingRef.current) {
      updateFromClientX(e.clientX);
    }
  };

  const handlePointerUp = (e: React.PointerEvent<HTMLDivElement>) => {
    draggingRef.current = false;
    if (e.currentTarget.hasPointerCapture(e.pointerId)) {
      e.currentTarget.releasePointerCapture(e.pointerId);
    }
  };

  const handleKeyDown = (e: React.KeyboardEvent<HTMLDivElement>) => {
    const step = e.shiftKey ? 10 : 2;
    if (e.key === 'ArrowLeft' || e.key === 'ArrowDown') {
      e.preventDefault();
      setPosition((p) => Math.max(0, p - step));
    } else if (e.key === 'ArrowRight' || e.key === 'ArrowUp') {
      e.preventDefault();
      setPosition((p) => Math.min(100, p + step));
    } else if (e.key === 'Home') {
      e.preventDefault();
      setPosition(0);
    } else if (e.key === 'End') {
      e.preventDefault();
      setPosition(100);
    }
  };

  const rounded = Math.round(position);

  return (
    <div
      ref={containerRef}
      className="compare"
      role="slider"
      tabIndex={0}
      aria-label="Image comparison"
      aria-orientation="horizontal"
      aria-valuemin={0}
      aria-valuemax={100}
      aria-valuenow={rounded}
      aria-valuetext={`Original visible across ${rounded} percent of the image`}
      onPointerDown={handlePointerDown}
      onPointerMove={handlePointerMove}
      onPointerUp={handlePointerUp}
      onPointerCancel={handlePointerUp}
      onKeyDown={handleKeyDown}
    >
      <img
        src={resultUrl}
        alt={resultAlt}
        className="compare-img"
        draggable={false}
      />
      <img
        src={originalUrl}
        alt={originalAlt}
        className="compare-img compare-original"
        style={{ clipPath: `inset(0 ${100 - position}% 0 0)` }}
        draggable={false}
      />

      <span className="compare-tag start">{startLabel}</span>
      <span className="compare-tag end">{endLabel}</span>

      <div className="compare-divider" style={{ left: `${position}%` }}>
        <span className="compare-handle" aria-hidden="true">
          <svg width="14" height="14" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2.5">
            <polyline points="9 18 3 12 9 6" />
            <polyline points="15 18 21 12 15 6" />
          </svg>
        </span>
      </div>
    </div>
  );
};
