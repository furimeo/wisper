import type {Catalog} from '../translate'

/**
 * Khung ứng dụng: điều hướng, menu tài khoản, các trạng thái dùng chung mọi màn hình.
 *
 * <p>Giữ nguyên thuật ngữ kỹ thuật đã quen trong giới vận hành - node, backup, API token -
 * vì dịch chúng ra tiếng Việt làm người đọc phải dịch ngược lại trong đầu để đối chiếu với
 * tài liệu và với chính giao diện Docker họ đang dùng.
 */
export const messages: Catalog = {
  'shell.skipToContent': 'Tới nội dung chính',

  'shell.nav.projects': 'Dự án',
  'shell.nav.databases': 'Cơ sở dữ liệu',
  'shell.nav.backups': 'Sao lưu',
  'shell.nav.organizations': 'Tổ chức',
  'shell.nav.profile': 'Hồ sơ',
  'shell.nav.security': 'Bảo mật',
  'shell.nav.apiTokens': 'API token',
  'shell.nav.nodes': 'Node',
  'shell.nav.placement': 'Phân bổ',
  'shell.nav.tenants': 'Khách thuê',
  'shell.nav.plans': 'Gói dịch vụ',
  'shell.nav.accounts': 'Tài khoản',
  'shell.nav.databaseEngines': 'Máy chủ cơ sở dữ liệu',
  'shell.nav.backupDestinations': 'Nơi lưu sao lưu',
  'shell.nav.auditLog': 'Nhật ký kiểm toán',
  'shell.nav.jobs': 'Tác vụ nền',
  'shell.nav.sectionAccount': 'Tài khoản',
  'shell.nav.sectionPlatform': 'Nền tảng',
  'shell.nav.open': 'Mở menu điều hướng',

  'shell.account.title': 'Tài khoản',
  'shell.account.label': 'Tài khoản: {name}',
  'shell.account.platformOperator': 'Quản trị nền tảng',
  'shell.account.signOut': 'Đăng xuất',
  'shell.account.theme': 'Giao diện',
  'shell.account.language': 'Ngôn ngữ',

  'shell.theme.light': 'Sáng',
  'shell.theme.dark': 'Tối',
  'shell.theme.system': 'Theo hệ thống',

  'shell.action.close': 'Đóng',
  'shell.action.cancel': 'Huỷ',
  'shell.action.save': 'Lưu',
  'shell.action.copy': 'Sao chép',
  'shell.action.copied': 'Đã sao chép',
  'shell.action.retry': 'Thử lại',

  'shell.state.loading': 'Đang tải',
  'shell.state.empty': 'Chưa có gì ở đây',
  'shell.state.error': 'Có gì đó không ổn',

  'shell.pagination.summary': '{from}-{to} trong {total} {unit}',
  'shell.pagination.previous': 'Trước',
  'shell.pagination.next': 'Sau',

  'shell.time.justNow': 'vừa xong',
  'shell.confirm.typeToConfirm': 'Gõ {phrase} để xác nhận',
}
