import { Injectable } from '@angular/core';
import { HttpClient, HttpEvent, HttpRequest } from '@angular/common/http';
import { Observable } from 'rxjs';

export type OcrMode = 'VISION' | 'GEMINI';

export interface OcrJob {
    id: string;
    originalFileName: string;
    totalPages: number;
    totalParts: number;
    completedParts: number;
    status: string;
    ocrMode: OcrMode;
    progressPercent: number;
    createdAt: string;
    updatedAt: string;
    tasks?: OcrTask[];
}

export interface OcrTask {
    id: string;
    partNumber: number;
    startPage: number;
    endPage: number;
    status: string;
    errorMessage: string;
    createdAt: string;
    updatedAt: string;
}

export interface OcrConfig {
    splitParts: number;
    splitDir: string;
    outputDir: string;
}

@Injectable({
    providedIn: 'root'
})
export class OcrService {
    private apiUrl = 'http://localhost:8080/api/ocr';

    constructor(private http: HttpClient) { }

    uploadPdf(file: File, numParts: number, splitDir: string, outputDir: string,
        ocrMode: OcrMode): Observable<HttpEvent<OcrJob>> {
        const formData = new FormData();
        formData.append('file', file);
        formData.append('numParts', numParts.toString());
        formData.append('ocrMode', ocrMode);
        if (splitDir) {
            formData.append('splitDir', splitDir);
        }
        if (outputDir) {
            formData.append('outputDir', outputDir);
        }

        const req = new HttpRequest('POST', `${this.apiUrl}/upload`, formData, {
            reportProgress: true,
        });

        return this.http.request<OcrJob>(req);
    }

    getJobs(): Observable<OcrJob[]> {
        return this.http.get<OcrJob[]>(`${this.apiUrl}/jobs`);
    }

    /** Xoá lịch sử job; includeActive = true thì xoá cả job chưa xong (đang chờ/đang chạy/treo). */
    clearHistory(includeActive: boolean): Observable<{ deleted: number }> {
        return this.http.delete<{ deleted: number }>(`${this.apiUrl}/jobs`, {
            params: { includeActive: String(includeActive) }
        });
    }

    getJob(id: string): Observable<OcrJob> {
        return this.http.get<OcrJob>(`${this.apiUrl}/jobs/${id}`);
    }

    downloadResult(id: string): Observable<Blob> {
        return this.http.get(`${this.apiUrl}/jobs/${id}/download`, {
            responseType: 'blob'
        });
    }

    getConfig(): Observable<OcrConfig> {
        return this.http.get<OcrConfig>(`${this.apiUrl}/config`);
    }

    updateConfig(config: OcrConfig): Observable<OcrConfig> {
        return this.http.put<OcrConfig>(`${this.apiUrl}/config`, config);
    }
}
