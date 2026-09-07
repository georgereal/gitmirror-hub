import React, { useState, useRef, useEffect } from 'react';
import { Info, X, Lightbulb, Cog, HelpCircle } from 'lucide-react';

export interface InfoTooltipProps {
  title: string;
  badge?: string;
  whatIsIt: string;
  howItWorks?: string;
  recommended?: string;
  position?: 'top' | 'bottom' | 'left' | 'right';
  className?: string;
  iconSize?: number;
}

export const InfoTooltip: React.FC<InfoTooltipProps> = ({
  title,
  badge,
  whatIsIt,
  howItWorks,
  recommended,
  position = 'top',
  className = '',
  iconSize = 13,
}) => {
  const [isOpen, setIsOpen] = useState(false);
  const containerRef = useRef<HTMLDivElement>(null);

  useEffect(() => {
    const handleClickOutside = (event: MouseEvent) => {
      if (containerRef.current && !containerRef.current.contains(event.target as Node)) {
        setIsOpen(false);
      }
    };

    if (isOpen) {
      document.addEventListener('mousedown', handleClickOutside);
    }
    return () => {
      document.removeEventListener('mousedown', handleClickOutside);
    };
  }, [isOpen]);

  const getPositionClasses = () => {
    switch (position) {
      case 'bottom':
        return 'top-full left-1/2 -translate-x-1/2 mt-2';
      case 'left':
        return 'right-full top-1/2 -translate-y-1/2 mr-2';
      case 'right':
        return 'left-full top-1/2 -translate-y-1/2 ml-2';
      case 'top':
      default:
        return 'bottom-full left-1/2 -translate-x-1/2 mb-2';
    }
  };

  return (
    <div
      ref={containerRef}
      className={`relative inline-flex items-center align-middle ${className}`}
      onMouseEnter={() => setIsOpen(true)}
      onMouseLeave={() => setIsOpen(false)}
    >
      <button
        type="button"
        onClick={(e) => {
          e.preventDefault();
          e.stopPropagation();
          setIsOpen(!isOpen);
        }}
        className="text-zinc-400 hover:text-zinc-700 dark:hover:text-zinc-200 focus:outline-none p-0.5 rounded-full hover:bg-zinc-100 transition-colors cursor-help"
        aria-label={`Information about ${title}`}
      >
        <Info style={{ width: iconSize, height: iconSize }} />
      </button>

      {isOpen && (
        <div
          className={`absolute z-50 w-72 sm:w-80 p-3.5 bg-zinc-900 text-white rounded-xl shadow-2xl border border-zinc-700/80 text-left text-xs pointer-events-auto transition-all animate-in fade-in duration-150 ${getPositionClasses()}`}
          onClick={(e) => e.stopPropagation()}
        >
          {/* Header */}
          <div className="flex items-center justify-between pb-2 mb-2 border-b border-zinc-800">
            <div className="flex items-center space-x-1.5 font-semibold text-zinc-100 text-[12px]">
              <HelpCircle className="w-3.5 h-3.5 text-blue-400 shrink-0" />
              <span>{title}</span>
            </div>
            {badge && (
              <span className="px-1.5 py-0.5 text-[9px] font-medium uppercase tracking-wider rounded bg-zinc-800 text-blue-300 border border-zinc-700">
                {badge}
              </span>
            )}
          </div>

          {/* Body Content */}
          <div className="space-y-2 text-[11px] leading-relaxed text-zinc-300 font-normal">
            <div>
              <span className="text-zinc-400 font-medium block text-[10px] uppercase tracking-wider mb-0.5">
                What it is
              </span>
              <p>{whatIsIt}</p>
            </div>

            {howItWorks && (
              <div>
                <span className="text-zinc-400 font-medium flex items-center space-x-1 text-[10px] uppercase tracking-wider mb-0.5">
                  <Cog className="w-3 h-3 text-zinc-400 inline" />
                  <span>How it works & Impact</span>
                </span>
                <p className="text-zinc-300">{howItWorks}</p>
              </div>
            )}

            {recommended && (
              <div className="pt-1.5 mt-1 border-t border-zinc-800/80 flex items-start space-x-1.5 text-amber-300/90 text-[10.5px]">
                <Lightbulb className="w-3.5 h-3.5 text-amber-400 shrink-0 mt-0.5" />
                <div>
                  <strong className="text-amber-300 font-semibold">Recommended:</strong> {recommended}
                </div>
              </div>
            )}
          </div>
        </div>
      )}
    </div>
  );
};
