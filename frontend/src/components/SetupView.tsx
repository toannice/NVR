import { useState } from 'react'
import { Icon } from './Icon'
import { NetworkConfigSection, StorageConfigSection } from './SystemConfigSections'
import { RecordingScheduleSection } from './RecordingScheduleSection'
import { CloudSettingsSection } from './CloudSettingsSection'
import { EditCameraModal, OnvifCredentialsModal } from './CameraModals'
import { ManualCameraForm } from './ManualCameraForm'
import GatewaysPanel from './GatewaysPanel'
import IntegrationPanel from './IntegrationPanel'

export const SetupView = ({ setupTab, setSetupTab, cameras, setCameras, handleDeleteCamera, handleAddCamera, setupStatus, fetchCameras, addToast, setMiniPlayerCamId }: any) => {
  const [showOnvifModal, setShowOnvifModal] = useState(false);
  const [showManualModal, setShowManualModal] = useState(false);
  const [showCredentialsModal, setShowCredentialsModal] = useState(false);
  const [showEditModal, setShowEditModal] = useState(false);
  const [selectedDevice, setSelectedDevice] = useState<any>(null);
  const [editingCamera, setEditingCamera] = useState<any>(null);
  const [onvifDevices, setOnvifDevices] = useState<any[]>([]);
  const [isScanning, setIsScanning] = useState(false);
  const [isAddingDevice, setIsAddingDevice] = useState(false);

  const scanOnvifDevices = async () => {
    setIsScanning(true);
    try {
      const res = await fetch('/api/onvif/scan');
      const data = await res.json();
      setOnvifDevices(data.devices || []);
    } catch (e) {
      console.error(e);
      addToast({ type: 'error', title: 'Lỗi ONVIF', message: 'Không thể quét thiết bị: ' + (e as Error).message });
    } finally {
      setIsScanning(false);
    }
  };

  // 3-state PTZ classification: 'none' | 'zoom' | 'full'. Persists both ptzEnabled + ptzMode server-side.
  const updatePtzMode = async (id: string, mode: 'none' | 'zoom' | 'full') => {
    try {
      const res = await fetch(`/api/cameras/${id}/ptz-enabled`, {
        method: 'PATCH',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify({ mode })
      });
      if (res.ok) {
        setCameras((prev: any[]) => prev.map(c => c.id === id ? { ...c, ptzEnabled: mode !== 'none', ptzMode: mode } : c));
        addToast({ type: 'success', title: 'Thành công', message: 'Đã cập nhật cấu hình PTZ' });
      } else {
        const errorData = await res.json().catch(() => ({}));
        throw new Error(errorData.error || 'Lỗi không xác định');
      }
    } catch (e: any) {
      addToast({ type: 'error', title: 'Lỗi', message: `Không thể cập nhật PTZ: ${e.message}` });
    }
  };

  const handleAddOnvifDevice = async (device: any, data: { username: string, password: string, name: string }) => {
    setIsAddingDevice(true);
    // Update device name with the one from the modal
    const updatedDevice = { ...device, name: data.name };
    const credentials = { username: data.username, password: data.password };

    try {
      const res = await fetch('/api/onvif/add-device', {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify({ device: updatedDevice, credentials })
      });
      const resData = await res.json();
      if (res.ok) {
        addToast({ type: 'success', title: 'Thêm thành công', message: `Camera ${data.name} đã được thêm!` });
        setOnvifDevices(prev => prev.map(d => d.id === device.id ? { ...d, isAdded: true, name: data.name } : d));
        setShowCredentialsModal(false);
        // Refresh cameras list automatically
        if (fetchCameras) await fetchCameras();
      } else {
        addToast({ type: 'error', title: 'Lỗi thêm camera', message: resData.error || 'Không thể thêm camera' });
      }
    } catch (e) {
      console.error(e);
      addToast({ type: 'error', title: 'Lỗi kết nối', message: (e as Error).message });
    } finally {
      setIsAddingDevice(false);
    }
  };

  return (
    <div className="flex flex-1 overflow-hidden h-full">
      <aside className="w-64 bg-white dark:bg-[#1F2937] border-r border-gray-200 dark:border-gray-700 flex flex-col shrink-0">
        <div className="p-4 border-b border-gray-200 dark:border-gray-700">
          <h2 className="text-sm font-bold text-gray-500 uppercase tracking-wider">Thiết lập Camera</h2>
        </div>
        <ul className="flex-1 overflow-y-auto py-2">
          {[
            { id: 'Camera List', icon: 'list', label: 'Danh sách Camera' },
            { id: 'Network', icon: 'settings_ethernet', label: 'Cấu hình mạng' },
            { id: 'Recording Schedule', icon: 'schedule', label: 'Lịch trình ghi hình' },
            { id: 'Storage', icon: 'storage', label: 'Lưu trữ' },
            { id: 'Cloud', icon: 'cloud', label: 'Kết nối Cloud' },
            { id: 'Gateway', icon: 'router', label: 'Gateway' },
            { id: 'Tích hợp', icon: 'extension', label: 'Tích hợp' }
          ].map((item) => (
            <li key={item.id}>
              <button
                onClick={() => setSetupTab(item.id)}
                className={`w-full flex items-center px-6 py-3 transition-colors text-left ${setupTab === item.id
                  ? 'bg-red-50 dark:bg-red-900/20 text-[#EF4444] font-medium border-r-4 border-[#EF4444]'
                  : 'text-gray-600 dark:text-gray-300 hover:bg-gray-100 dark:hover:bg-gray-700'
                  }`}
              >
                <Icon name={item.icon} className="mr-3 text-xl" /> {item.label}
              </button>
            </li>
          ))}
        </ul>
      </aside>

      <div className="flex-1 bg-gray-100 dark:bg-gray-900 p-8 overflow-y-auto">
        {setupTab === 'Camera List' && (
          <>
            <div className="flex justify-between items-center mb-6">
              <h1 className="text-2xl font-bold text-gray-800 dark:text-white flex items-center">
                <Icon name="videocam" className="text-[#EF4444] mr-3 text-3xl" />
                Danh sách Camera
              </h1>
              <button
                onClick={() => {
                  setShowOnvifModal(true);
                  scanOnvifDevices();
                }}
                className="flex items-center px-4 py-2 bg-[#EF4444] text-white rounded shadow-md hover:bg-red-600 transition-colors"
              >
                <Icon name="add" className="text-xl mr-2" />
                Thêm mới
              </button>
            </div>

            <div className="bg-white dark:bg-[#1F2937] rounded-lg shadow border border-gray-200 dark:border-gray-700 overflow-hidden">
              <table className="w-full text-left border-collapse">
                <thead className="bg-gray-50 dark:bg-[#252525]">
                  <tr>
                    <th className="p-4 text-xs font-semibold tracking-wide text-gray-500 dark:text-gray-400 uppercase border-b border-gray-200 dark:border-gray-700 w-16 text-center">STT</th>
                    <th className="p-4 text-xs font-semibold tracking-wide text-gray-500 dark:text-gray-400 uppercase border-b border-gray-200 dark:border-gray-700">Tên Camera</th>
                    <th className="p-4 text-xs font-semibold tracking-wide text-gray-500 dark:text-gray-400 uppercase border-b border-gray-200 dark:border-gray-700">Địa chỉ IP</th>
                    <th className="p-4 text-xs font-semibold tracking-wide text-gray-500 dark:text-gray-400 uppercase border-b border-gray-200 dark:border-gray-700 text-center">Trạng thái</th>
                    <th className="p-4 text-xs font-semibold tracking-wide text-gray-500 dark:text-gray-400 uppercase border-b border-gray-200 dark:border-gray-700 text-center">PTZ</th>
                    <th className="p-4 text-xs font-semibold tracking-wide text-gray-500 dark:text-gray-400 uppercase border-b border-gray-200 dark:border-gray-700 text-center w-40">Hành động</th>
                  </tr>
                </thead>
                <tbody className="divide-y divide-gray-200 dark:divide-gray-700 bg-white dark:bg-[#1F2937]">
                  {cameras.map((c: any, idx: number) => (
                    <tr key={c.name} className="hover:bg-gray-50 dark:hover:bg-[#2A2A2A] transition-colors group">
                      <td className="p-4 text-sm text-center text-gray-500 dark:text-gray-400">{idx + 1}</td>
                      <td className="p-4 text-sm font-medium text-gray-900 dark:text-white">
                        <div className="flex items-center">
                          <span className="w-8 h-8 rounded bg-gray-200 dark:bg-gray-700 flex items-center justify-center mr-3 text-gray-500">
                            <Icon name="videocam" className="text-lg" />
                          </span>
                          {c.name}
                        </div>
                      </td>
                      <td className="p-4 text-sm text-gray-600 dark:text-gray-300 font-mono">{c.ip || 'N/A'}</td>
                      <td className="p-4 text-center">
                        <span className={`inline-flex items-center px-2.5 py-0.5 rounded-full text-xs font-medium ${c.isOnline
                          ? 'bg-green-100 text-green-800 dark:bg-green-900/30 dark:text-green-400'
                          : 'bg-red-100 text-red-800 dark:bg-red-900/30 dark:text-red-400'
                          }`}>
                          <span className={`w-1.5 h-1.5 rounded-full ${c.isOnline ? 'bg-green-500' : 'bg-red-500'} mr-1.5`}></span>
                          {c.isOnline ? 'Online' : 'Offline'}
                        </span>
                      </td>
                      <td className="p-4 text-center">
                        <select
                          value={!c.ptzEnabled ? 'none' : (c.ptzMode === 'zoom' ? 'zoom' : 'full')}
                          onChange={e => updatePtzMode(c.id, e.target.value as 'none' | 'zoom' | 'full')}
                          className="text-xs rounded border-gray-300 dark:border-gray-600 dark:bg-gray-800 dark:text-white focus:ring-indigo-500 focus:border-indigo-500 py-1"
                          title="Phân loại PTZ của camera"
                        >
                          <option value="none">Không PTZ</option>
                          <option value="zoom">Chỉ Zoom</option>
                          <option value="full">PTZ đầy đủ</option>
                        </select>
                      </td>
                      <td className="p-4">
                        <div className="flex justify-center space-x-2">
                          <button
                            onClick={() => {
                              // Open Mini Player instead of navigating
                              setMiniPlayerCamId(c.id);
                            }}
                            className="p-1.5 text-gray-500 hover:text-blue-600 hover:bg-blue-50 dark:hover:bg-blue-900/20 rounded transition-colors"
                            title="Xem nhanh (Mini Player)"
                          >
                            <Icon name="visibility" className="text-xl" />
                          </button>
                          <button
                            onClick={() => {
                              setEditingCamera(c);
                              setShowEditModal(true);
                            }}
                            className="p-1.5 text-gray-500 hover:text-gray-800 hover:bg-gray-100 dark:hover:bg-gray-700 rounded transition-colors"
                            title="Cấu hình"
                          >
                            <Icon name="settings" className="text-xl" />
                          </button>
                          <button
                            onClick={() => handleDeleteCamera(c.id)}
                            className="p-1.5 text-gray-500 hover:text-red-600 hover:bg-red-50 rounded transition-colors"
                            title="Xóa"
                          >
                            <Icon name="delete" className="text-xl" />
                          </button>
                        </div>
                      </td>
                    </tr>
                  ))}
                </tbody>
              </table>
              {cameras.length === 0 && (
                <div className="p-10 text-center text-gray-500 dark:text-gray-400">Chưa có camera nào</div>
              )}
            </div>
          </>
        )}

        {setupTab === 'Add Camera' && (
          <div className="p-10 text-center text-gray-500 dark:text-gray-400">
            Chức năng thêm camera đã được chuyển vào tab "Danh sách Camera" → Nút "Thêm mới"
          </div>
        )}

        {setupTab === 'Network' && <NetworkConfigSection />}
        {setupTab === 'Recording Schedule' && <RecordingScheduleSection cameras={cameras} addToast={addToast} />}
        {setupTab === 'Storage' && <StorageConfigSection />}
        {setupTab === 'Cloud' && <CloudSettingsSection />}
        {setupTab === 'Gateway' && (
          <div className="-m-8">
            <GatewaysPanel addToast={addToast} onCamerasChanged={fetchCameras} />
          </div>
        )}
        {setupTab === 'Tích hợp' && (
          <div className="-m-8">
            <IntegrationPanel addToast={addToast} />
          </div>
        )}
      </div>

      {showOnvifModal && (
        <div className="fixed inset-0 z-50 flex items-center justify-center bg-black/60 p-8">
          <div className="bg-white dark:bg-[#1F2937] w-full max-w-5xl rounded-lg shadow-2xl flex flex-col max-h-[90vh] border border-gray-200 dark:border-gray-700 overflow-hidden">
            <div className="px-6 py-4 border-b border-gray-200 dark:border-gray-700 flex justify-between items-center">
              <h2 className="text-xl font-bold text-[#EF4444] flex items-center">
                <Icon name="travel_explore" className="mr-2" />
                Thêm Camera qua LAN/ONVIF
              </h2>
              <button onClick={() => setShowOnvifModal(false)} className="text-gray-400 hover:text-gray-600">
                <Icon name="close" />
              </button>
            </div>

            <div className="p-6 flex-1 overflow-hidden flex flex-col min-h-0">
              <div className="flex justify-between items-center mb-6">
                <div className="text-sm text-gray-500">
                  Tìm thấy <span className="font-bold text-[#EF4444]">{onvifDevices.length}</span> thiết bị ONVIF
                </div>
                <button
                  onClick={scanOnvifDevices}
                  disabled={isScanning}
                  className="px-4 py-2 bg-gray-200 dark:bg-gray-700 text-gray-700 dark:text-gray-300 rounded hover:bg-gray-300 flex items-center text-sm font-medium disabled:opacity-50"
                >
                  <Icon name="refresh" className="text-lg mr-1" /> {isScanning ? 'Đang quét...' : 'Quét lại'}
                </button>
                <button
                  onClick={() => {
                    setShowOnvifModal(false);
                    setShowManualModal(true);
                  }}
                  className="px-4 py-2 bg-[#EF4444] text-white rounded hover:bg-red-600 transition-colors flex items-center text-sm font-medium shadow-sm"
                >
                  <Icon name="add" className="text-lg mr-1" /> Thêm thủ công
                </button>
              </div>

              <div className="flex-1 overflow-auto border rounded min-h-0">
                <table className="w-full text-left">
                  <thead className="bg-gray-50 dark:bg-[#252525] sticky top-0">
                    <tr>
                      <th className="p-3 text-xs font-semibold text-gray-500 uppercase">STT</th>
                      <th className="p-3 text-xs font-semibold text-gray-500 uppercase">Tên Camera</th>
                      <th className="p-3 text-xs font-semibold text-gray-500 uppercase">IP</th>
                      <th className="p-3 text-xs font-semibold text-gray-500 uppercase">Trạng thái</th>
                      <th className="p-3 text-xs font-semibold text-gray-500 uppercase text-right">Hành động</th>
                    </tr>
                  </thead>
                  <tbody className="divide-y">
                    {onvifDevices.map((device, idx) => (
                      <tr key={device.id} className="hover:bg-gray-50 dark:hover:bg-[#2A2A2A]">
                        <td className="p-3 text-sm text-center">{idx + 1}</td>
                        <td className="p-3 text-sm font-medium">
                          <Icon name="videocam" className="inline mr-2" />{device.name}
                        </td>
                        <td className="p-3 text-sm font-mono">{device.ip}</td>
                        <td className="p-3">
                          {device.status === 'online' && (
                            <span className="inline-flex items-center px-2 py-0.5 rounded-full text-xs bg-green-100 text-green-800">
                              <span className="w-1.5 h-1.5 rounded-full bg-green-500 mr-1"></span>Online
                            </span>
                          )}
                        </td>
                        <td className="p-3 text-right">
                          {device.isAdded ? (
                            <button className="px-3 py-1 bg-gray-200 text-gray-400 rounded text-sm" disabled>
                              Đã thêm
                            </button>
                          ) : (
                            <button
                              onClick={() => {
                                setSelectedDevice(device);
                                setShowCredentialsModal(true);
                              }}
                              className="px-3 py-1 bg-[#EF4444]/10 hover:bg-[#EF4444] text-[#EF4444] hover:text-white border border-[#EF4444]/20 rounded text-sm transition"
                            >
                              Thêm
                            </button>
                          )}
                        </td>
                      </tr>
                    ))}
                  </tbody>
                </table>
              </div>
            </div>

            <div className="px-6 py-4 bg-gray-50 dark:bg-[#252525] border-t flex justify-end gap-3">
              <button onClick={() => setShowOnvifModal(false)} className="px-4 py-2 border rounded">Xong</button>
            </div>
          </div>
        </div>
      )}

      {/* Credentials Modal for ONVIF Device */}
      {showCredentialsModal && selectedDevice && (
        <OnvifCredentialsModal
          device={selectedDevice}
          onClose={() => {
            setShowCredentialsModal(false);
            setSelectedDevice(null);
          }}
          onSubmit={(credentials: any) => handleAddOnvifDevice(selectedDevice, credentials)}
          isLoading={isAddingDevice}
        />
      )}

      {/* Camera Edit Modal */}
      {showEditModal && editingCamera && (
        <EditCameraModal
          device={editingCamera}
          onClose={() => {
            setShowEditModal(false);
            setEditingCamera(null);
          }}
          onSubmit={async (payload: { name: string; onvifPort: number; onvifUsername?: string; onvifPassword?: string; ptzMode?: string }) => {
            try {
              const res = await fetch('/api/edit-camera', {
                method: 'POST',
                headers: { 'Content-Type': 'application/json' },
                body: JSON.stringify({
                  id: editingCamera.original_slug || editingCamera.id,
                  name: payload.name,
                  onvifPort: payload.onvifPort,
                  onvifUsername: payload.onvifUsername,
                  onvifPassword: payload.onvifPassword,
                  ptzMode: payload.ptzMode,
                })
              });
              const data = await res.json();
              if (!res.ok) throw new Error(data.error || 'Failed to rename');
              addToast({ type: 'success', title: 'Thành công', message: data.message });
              setShowEditModal(false);
              setEditingCamera(null);
              fetchCameras(); // Refresh the list
            } catch (e: any) {
              addToast({ type: 'error', title: 'Lỗi', message: e.message });
            }
          }}
          isLoading={false}
        />
      )}


      {/* Manual Add Camera Modal */}
      {showManualModal && (
        <ManualCameraForm
          onClose={() => setShowManualModal(false)}
          onAdd={handleAddCamera}
          status={setupStatus}
          addToast={addToast}
        />
      )}
    </div>
  );
};
