import { Icon } from './Icon'

// Toast Notification Component
export const ToastContainer = ({ toasts, removeToast }: any) => {
  return (
    <div className="fixed bottom-4 right-4 z-[9999] flex flex-col gap-2">
      {toasts.map((toast: any) => (
        <div
          key={toast.key}
          className={`flex items-center p-4 rounded-lg shadow-lg text-white min-w-[300px] animate-fade-in-up transition-all duration-300 ${toast.type === 'success' ? 'bg-green-600' :
            toast.type === 'error' ? 'bg-red-600' :
            toast.type === 'warning' ? 'bg-yellow-600' : 'bg-blue-600'
            }`}
        >
          <Icon name={toast.type === 'success' ? 'check_circle' : toast.type === 'error' ? 'error' : toast.type === 'warning' ? 'warning' : 'info'} className="mr-3 text-2xl" />
          <div className="flex-1">
            <h4 className="font-bold text-sm">{toast.title}</h4>
            <p className="text-sm opacity-90">{toast.message}</p>
          </div>
          <button onClick={() => removeToast(toast.key)} className="ml-2 hover:bg-white/20 rounded-full p-1 transition-colors">
            <Icon name="close" className="text-sm" />
          </button>
        </div>
      ))}
    </div>
  );
};
