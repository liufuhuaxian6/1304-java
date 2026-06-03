const { createApp, ref, reactive, onMounted } = Vue;

const API_BASE = 'http://localhost:8082/api/v1';

createApp({
    setup() {
        const user = ref(null);
        const loginForm = reactive({ username: '', password: '' });
        const error = ref('');
        const toast = ref('');
        
        const currentView = ref('list'); // 'list', 'editor'
        const documents = ref([]);
        
        const currentDocument = ref(null);
        const documentContent = ref('');
        const isTextFile = ref(false);
        const isTruncated = ref(false);
        const hasEditLock = ref(false);

        // Version management state
        const showVersions = ref(false);
        const versions = ref([]);
        const versionDoc = ref(null);

        // Utility: Show toast
        const showToast = (msg) => {
            toast.value = msg;
            setTimeout(() => toast.value = '', 3000);
        };

        // Utility: API call wrapper
        const apiCall = async (endpoint, options = {}) => {
            const token = localStorage.getItem('token');
            const headers = { ...options.headers };
            
            if (token) {
                headers['Authorization'] = `Bearer ${token}`;
            }
            if (!options.body || !(options.body instanceof FormData)) {
                headers['Content-Type'] = 'application/json';
            }

            try {
                const res = await fetch(`${API_BASE}${endpoint}`, { ...options, headers });
                
                if (res.status === 401 && endpoint !== '/auth/login') {
                    logout();
                    throw new Error('会话过期，请重新登录');
                }

                if (options.isDownload) {
                    if (!res.ok) throw new Error('下载失败');
                    return res;
                }

                const data = await res.json();
                if (!data.success) {
                    throw new Error(data.message || '请求失败');
                }
                return data.data;
            } catch (err) {
                error.value = err.message;
                showToast(err.message);
                throw err;
            }
        };

        // Auth methods
        const login = async () => {
            error.value = '';
            try {
                const data = await apiCall('/auth/login', {
                    method: 'POST',
                    body: JSON.stringify(loginForm)
                });
                localStorage.setItem('token', data.token);
                user.value = data.user;
                fetchDocuments();
            } catch (err) {
                // Handled in apiCall
            }
        };

        const logout = async () => {
            try {
                await apiCall('/auth/logout', { method: 'POST' });
            } catch (e) {}
            localStorage.removeItem('token');
            user.value = null;
            loginForm.username = '';
            loginForm.password = '';
        };

        const checkAuth = async () => {
            if (localStorage.getItem('token')) {
                try {
                    const data = await apiCall('/auth/me');
                    user.value = data;
                    fetchDocuments();
                } catch (e) {
                    localStorage.removeItem('token');
                }
            }
        };

        // Document list methods
        const fetchDocuments = async () => {
            try {
                const docs = await apiCall('/documents');
                documents.value = docs || [];
            } catch (err) {}
        };

        const uploadDocument = async (event) => {
            const file = event.target.files[0];
            if (!file) return;

            const formData = new FormData();
            formData.append('file', file);

            try {
                const headers = { 'Authorization': `Bearer ${localStorage.getItem('token')}` };
                const res = await fetch(`${API_BASE}/documents`, {
                    method: 'POST',
                    headers,
                    body: formData
                });
                const data = await res.json();
                if (!data.success) throw new Error(data.message);
                
                showToast('上传成功');
                fetchDocuments();
            } catch (err) {
                showToast(err.message || '上传失败');
            }
            event.target.value = ''; // Reset input
        };

        const downloadDocument = async (doc) => {
            try {
                const res = await apiCall(`/documents/${doc.documentId}/download`, { isDownload: true });
                const blob = await res.blob();
                const url = window.URL.createObjectURL(blob);
                const a = document.createElement('a');
                a.href = url;
                a.download = doc.fileName;
                document.body.appendChild(a);
                a.click();
                window.URL.revokeObjectURL(url);
                document.body.removeChild(a);
                showToast('下载成功');
            } catch (err) {}
        };

        // Editor methods
        const viewDocument = async (doc) => {
            try {
                const data = await apiCall(`/documents/${doc.documentId}/preview`);
                currentDocument.value = data.document;
                documentContent.value = data.contentText || '';
                isTextFile.value = data.isTextFile;
                isTruncated.value = data.truncated;
                hasEditLock.value = data.document.editingUser === user.value.username;
                currentView.value = 'editor';
            } catch (err) {}
        };

        const requestEditLock = async () => {
            try {
                await apiCall(`/documents/${currentDocument.value.documentId}/lock`, { method: 'POST' });
                hasEditLock.value = true;
                currentDocument.value.editingUser = user.value.username;
                showToast('已获取编辑权限');
            } catch (err) {}
        };

        const releaseEditLock = async () => {
            try {
                await apiCall(`/documents/${currentDocument.value.documentId}/lock`, { method: 'DELETE' });
                hasEditLock.value = false;
                currentDocument.value.editingUser = null;
                showToast('已释放编辑权限');
            } catch (err) {}
        };

        const saveDocument = async () => {
            try {
                await apiCall(`/documents/${currentDocument.value.documentId}/content`, {
                    method: 'PUT',
                    body: JSON.stringify({ contentText: documentContent.value })
                });
                showToast('保存成功');
            } catch (err) {}
        };

        const backToList = async () => {
            if (hasEditLock.value) {
                if (confirm('您当前处于编辑状态，是否退出并释放编辑权限？')) {
                    await releaseEditLock();
                } else {
                    return;
                }
            }
            currentView.value = 'list';
            currentDocument.value = null;
            documentContent.value = '';
            fetchDocuments();
        };

        // Version management methods
        const opLabel = (op) => ({
            UPLOAD: '上传', EDIT: '编辑', ROLLBACK: '回滚', DOWNLOAD: '下载', VIEW: '查看'
        }[op] || op || '');

        const openVersions = async (doc) => {
            if (!doc) return;
            versionDoc.value = { documentId: doc.documentId, fileName: doc.fileName };
            versions.value = [];
            showVersions.value = true;
            try {
                const data = await apiCall(`/documents/${doc.documentId}/versions`);
                versions.value = data || [];
            } catch (err) {}
        };

        const closeVersions = () => {
            showVersions.value = false;
            versionDoc.value = null;
            versions.value = [];
        };

        const downloadVersion = async (v) => {
            try {
                const res = await apiCall(`/documents/${versionDoc.value.documentId}/versions/${v.versionId}/download`, { isDownload: true });
                const blob = await res.blob();
                const url = window.URL.createObjectURL(blob);
                const a = document.createElement('a');
                a.href = url;
                a.download = `${v.versionId}-${v.fileName}`;
                document.body.appendChild(a);
                a.click();
                window.URL.revokeObjectURL(url);
                document.body.removeChild(a);
                showToast('版本下载成功');
            } catch (err) {}
        };

        const rollbackVersion = async (v) => {
            if (!confirm(`确认将文档回滚到版本 ${v.versionId} 吗？此操作会生成一条新的回滚版本。`)) return;
            const docId = versionDoc.value.documentId;
            const fileName = versionDoc.value.fileName;
            try {
                await apiCall(`/documents/${docId}/versions/${v.versionId}/rollback`, { method: 'POST' });
                showToast('版本回滚成功');
                const data = await apiCall(`/documents/${docId}/versions`);
                versions.value = data || [];
                if (currentView.value === 'editor' && currentDocument.value && currentDocument.value.documentId === docId) {
                    await viewDocument({ documentId: docId, fileName });
                }
                fetchDocuments();
            } catch (err) {}
        };

        onMounted(() => {
            checkAuth();
        });

        return {
            user, loginForm, error, toast, currentView, documents,
            currentDocument, documentContent, isTextFile, isTruncated, hasEditLock,
            showVersions, versions, versionDoc,
            login, logout, fetchDocuments, uploadDocument, downloadDocument,
            viewDocument, requestEditLock, releaseEditLock, saveDocument, backToList,
            opLabel, openVersions, closeVersions, downloadVersion, rollbackVersion
        };
    }
}).mount('#app');